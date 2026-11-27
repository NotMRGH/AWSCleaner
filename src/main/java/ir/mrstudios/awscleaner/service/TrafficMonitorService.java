package ir.mrstudios.awscleaner.service;

import com.google.gson.Gson;
import ir.mrstudios.awscleaner.aws.LightsailClient;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.model.Lightsail.MetricPoint;
import ir.mrstudios.awscleaner.model.Lightsail.NetworkMetric;
import ir.mrstudios.awscleaner.model.Lightsail.TrafficInstance;
import ir.mrstudios.awscleaner.model.TrafficState;
import ir.mrstudios.awscleaner.model.TrafficState.Pool;
import ir.mrstudios.awscleaner.model.TrafficState.Snapshot;
import ir.mrstudios.awscleaner.model.TrafficState.Totals;
import ir.mrstudios.awscleaner.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class TrafficMonitorService {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficMonitorService.class);
    private static final Gson GSON = new Gson();
    private static final Pattern INSTANCE_ARN = Pattern.compile("arn:aws(?:-[a-z]+)*:lightsail:([^:]+):(\\d{12}):Instance/[A-Za-z0-9-]+");
    private static final String STANDARD_ALLOWANCE_REGION = "us-east-1";
    private static final Set<String> HALF_ALLOWANCE_REGIONS = Set.of(
            "ap-south-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-5", "ap-east-1", "sa-east-1");
    private static final Duration METRIC_DELAY = Duration.ofMinutes(10);
    private static final double GIB = 1L << 30;
    private static final double TIB = 1L << 40;
    private static final int CRITICAL_ICON_PERCENTAGE = 90;
    private static final int STARTUP_DETAILS_LIMIT = 3800;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final AppConfig.TrafficMonitor config;
    private final Map<String, LightsailClient> clients;
    private final TelegramClient telegram;
    private final RotationJournal journal;
    private final Clock clock;
    private final Map<String, Catalog> catalogs = new HashMap<>();
    private Map<String, String> regionNames;
    private String account;
    private TrafficState state;
    private boolean dirty;

    public TrafficMonitorService(AppConfig.TrafficMonitor config, Map<String, LightsailClient> clients,
                                 TelegramClient telegram, RotationJournal journal, Clock clock) {
        this.config = config;
        this.clients = Map.copyOf(clients);
        this.telegram = telegram;
        this.journal = journal;
        this.clock = clock;
    }

    public static String catalogRegion(String region) {
        return HALF_ALLOWANCE_REGIONS.contains(region) ? STANDARD_ALLOWANCE_REGION : region;
    }

    public void check() throws IOException, InterruptedException {
        check(false);
    }

    public void checkOnStartup() throws IOException, InterruptedException {
        if (config.checkOnStartup()) {
            check(true);
        } else {
            sendStartupStatus(clock.instant(), null);
        }
    }

    private void check(boolean startup) throws IOException, InterruptedException {
        Instant end = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var month = utcMonth(end);
        CycleResult result;
        try {
            result = collect(month, end);
        } catch (IOException | RuntimeException e) {
            if (startup) sendStartupStatus(end, null);
            throw e;
        }
        if (startup) sendStartupStatus(end, result);
        if (telegram != null) alert(month, end, result.reports());
    }

    private CycleResult collect(YearMonth month, Instant end) throws IOException, InterruptedException {
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        loadMonth(month);
        if (dirty) save(state);

        var inventories = inventories();
        var pools = new TreeMap<>(state.pools());
        var reports = new ArrayList<Report>();
        var failedCatalogs = new HashSet<String>();
        for (var entry : inventories.entrySet()) {
            String region = entry.getKey();
            var groups = new TreeMap<String, List<TrafficInstance>>();
            if (account == null) continue;
            String prefix = account + "/" + region + "/";
            pools.keySet().stream().filter(key -> key.startsWith(prefix))
                    .forEach(key -> groups.put(key, new ArrayList<>()));
            for (TrafficInstance instance : entry.getValue()) {
                String key = prefix + allowanceBundle(instance.bundleId());
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(instance);
            }
            for (var group : groups.entrySet()) {
                String key = group.getKey();
                String bundle = key.substring(prefix.length());
                var previous = pools.getOrDefault(key, Pool.empty());
                var current = measure(key, region, bundle, group.getValue(), previous, start, end, failedCatalogs);
                pools.put(key, current.pool());
                reports.add(current.report());
            }
        }
        for (Report report : reports) log(report);
        var next = new TrafficState(month.toString(), pools);
        if (!next.equals(state)) save(next);
        return new CycleResult(reports, inventories.keySet());
    }

    private Map<String, List<TrafficInstance>> inventories() throws InterruptedException {
        var inventories = new LinkedHashMap<String, List<TrafficInstance>>();
        for (String region : config.regions()) {
            try {
                var instances = clients.get(region).getInstances();
                var names = new HashSet<String>();
                var arns = new HashSet<String>();
                String regionAccount = account;
                for (TrafficInstance instance : instances) {
                    var arn = INSTANCE_ARN.matcher(instance.arn() == null ? "" : instance.arn());
                    if (!arn.matches() || !region.equals(arn.group(1))
                            || instance.name() == null || !instance.name().matches("[A-Za-z0-9_][A-Za-z0-9_-]*")
                            || instance.bundleId() == null || !instance.bundleId().matches("[A-Za-z0-9_]+")
                            || instance.createdAt() == null || !Double.isFinite(instance.createdAt())
                            || instance.createdAt() <= 0 || instance.createdAt() > Instant.MAX.getEpochSecond()
                            || !names.add(instance.name()) || !arns.add(instance.arn())) {
                        throw new IOException("Invalid or inconsistent GetInstances inventory");
                    }
                    if (regionAccount != null && !regionAccount.equals(arn.group(2))) {
                        throw new IOException("GetInstances returned inconsistent account identifiers");
                    }
                    regionAccount = arn.group(2);
                }
                account = regionAccount;
                inventories.put(region, instances);
                LOG.info("Lightsail traffic inventory in {} contains {} instance(s)", region, instances.size());
            } catch (IOException e) {
                LOG.warn("Lightsail traffic inventory unavailable in {}: {}", region, e.getMessage());
            }
        }
        return inventories;
    }

    private Measurement measure(String key, String region, String bundle, List<TrafficInstance> active, Pool previous,
                                Instant start, Instant end, Set<String> failedCatalogs) throws InterruptedException {
        var snapshots = new HashMap<>(previous.instances());
        int peak = Math.max(previous.peakInstances(), active.size());
        Double allowance = allowance(region, bundle, active, previous, end, failedCatalogs);
        Double totalAllowance = allowance == null ? null : peak * allowance;
        var known = new ArrayList<Totals>();
        var activeArns = new HashSet<String>();
        int missing = 0;
        for (TrafficInstance instance : active) {
            activeArns.add(instance.arn());
            Totals totals = metrics(region, instance, start, end);
            if (totals == null) {
                missing++;
                snapshots.putIfAbsent(instance.arn(), new Snapshot(null));
            } else {
                snapshots.put(instance.arn(), new Snapshot(totals));
                known.add(totals);
            }
        }
        int retired = 0;
        for (var snapshot : snapshots.entrySet()) {
            if (activeArns.contains(snapshot.getKey())) continue;
            retired++;
            Totals totals = snapshot.getValue().totals();
            if (totals == null) missing++;
            else known.add(totals);
        }
        Totals totals = known.isEmpty() ? null : new Totals(known.stream().mapToDouble(Totals::incoming).sum(),
                known.stream().mapToDouble(Totals::outgoing).sum());
        var variants = new TreeSet<String>();
        active.forEach(instance -> variants.add(instance.bundleId()));
        String plans = variants.isEmpty() ? bundle : String.join(" / ", variants);
        return new Measurement(new Pool(peak, allowance, snapshots, previous.processed()),
                new Report(key, region, plans, active.size(), peak, retired, missing, totalAllowance, totals));
    }

    private Double allowance(String region, String bundle, List<TrafficInstance> active, Pool previous,
                             Instant now, Set<String> failedCatalogs) throws InterruptedException {
        var quotas = new HashSet<Integer>();
        for (TrafficInstance instance : active) {
            Integer allocation = instance.allocatedTransfer();
            if (allocation != null && allocation > 0) quotas.add(allocation);
        }
        if (quotas.size() == 1) return quotas.iterator().next() * GIB;
        if (quotas.size() > 1) {
            LOG.warn("Lightsail traffic allowance differs across instances in {}/{}", region, bundle);
            return null;
        }
        if (previous.allowancePerInstance() != null) return previous.allowancePerInstance();
        String catalogRegion = catalogRegion(region);
        try {
            var catalog = catalogs.get(catalogRegion);
            if ((catalog == null || !now.isBefore(catalog.expiresAt())) && !failedCatalogs.contains(catalogRegion)) {
                var sizes = new HashMap<String, Integer>();
                var conflicts = new HashSet<String>();
                for (var plan : clients.get(catalogRegion).getBundles()) {
                    Integer size = plan.transferPerMonthInGb();
                    if (plan.bundleId() == null || size == null || size <= 0) continue;
                    String id = allowanceBundle(plan.bundleId());
                    Integer first = sizes.putIfAbsent(id, size);
                    if (first != null && !first.equals(size)) conflicts.add(id);
                }
                conflicts.forEach(sizes::remove);
                catalog = new Catalog(now.plus(Duration.ofDays(1)), sizes);
                catalogs.put(catalogRegion, catalog);
            }
            Integer size = catalog == null ? null : catalog.sizes().get(bundle);
            if (size != null) return size * GIB * (HALF_ALLOWANCE_REGIONS.contains(region) ? 0.5 : 1.0);
        } catch (IOException e) {
            failedCatalogs.add(catalogRegion);
            LOG.warn("Lightsail traffic bundle catalog unavailable in {}: {}", catalogRegion, e.getMessage());
        }
        return null;
    }

    private Totals metrics(String region, TrafficInstance instance, Instant start, Instant end) throws InterruptedException {
        if (!end.isAfter(start) || instance.createdAt() >= end.getEpochSecond()) return null;
        var created = Instant.ofEpochSecond(instance.createdAt().longValue());
        Instant firstHour = (created.isAfter(start) ? created : start).truncatedTo(ChronoUnit.HOURS);
        var client = clients.get(region);
        try {
            Double incoming = total(client.getNetworkMetric(instance.name(), NetworkMetric.NetworkIn, start, end), firstHour, end);
            Double outgoing = total(client.getNetworkMetric(instance.name(), NetworkMetric.NetworkOut, start, end), firstHour, end);
            if (incoming != null && outgoing != null) return new Totals(incoming, outgoing);
            LOG.warn("Incomplete Lightsail traffic metrics in {} for {} (NetworkIn {}, NetworkOut {})",
                    region, instance.name(), incoming == null ? "unavailable" : "available",
                    outgoing == null ? "unavailable" : "available");
        } catch (IOException e) {
            LOG.warn("Lightsail traffic metrics unavailable in {} for {}: {}", region, instance.name(), e.getMessage());
        }
        return null;
    }

    static Double total(List<MetricPoint> points, Instant firstHour, Instant end) {
        var hours = new HashSet<Long>();
        double sum = 0;
        for (MetricPoint point : points) {
            Double timestamp = point.timestamp();
            Double bytes = point.sum();
            if (timestamp == null || !Double.isFinite(timestamp) || bytes == null
                    || !Double.isFinite(bytes) || bytes < 0 || !"Bytes".equals(point.unit())) return null;
            if (timestamp < firstHour.getEpochSecond() || timestamp >= end.getEpochSecond()) continue;
            long hour = Instant.ofEpochSecond(timestamp.longValue()).truncatedTo(ChronoUnit.HOURS).getEpochSecond();
            if (!hours.add(hour)) return null;
            sum += bytes;
        }
        if (hours.isEmpty() || !Double.isFinite(sum)) return null;
        Instant settledThrough = end.minus(METRIC_DELAY).truncatedTo(ChronoUnit.HOURS);
        for (Instant hour = firstHour; hour.isBefore(settledThrough); hour = hour.plus(1, ChronoUnit.HOURS)) {
            if (!hours.contains(hour.getEpochSecond())) return null;
        }
        return sum;
    }

    private void alert(YearMonth month, Instant end, List<Report> reports) throws IOException, InterruptedException {
        for (Report report : reports) {
            if (!month.equals(utcMonth(clock.instant()))) return;
            if (report.percent() == null) continue;
            var pool = state.pools().get(report.key());
            var crossed = config.warningPercentages().stream()
                    .filter(level -> report.totals().consumed() * 100 >= report.allowance() * level
                            && !pool.processed().contains(level)).toList();
            if (crossed.isEmpty()) continue;
            int highest = crossed.stream().mapToInt(Integer::intValue).max().orElseThrow();
            String regionName = regionName(report.region());
            if (!month.equals(utcMonth(clock.instant()))) return;
            try {
                if (!telegram.sendHtml(message(report, regionName, highest, end))) {
                    LOG.info("Lightsail traffic alerts deferred by Telegram rate limit");
                    return;
                }
            } catch (IOException e) {
                LOG.warn("Lightsail traffic alert for {} failed: {}", report.key(), e.getMessage());
                return;
            }
            var processed = new HashSet<>(pool.processed());
            processed.addAll(crossed);
            var next = new TreeMap<>(state.pools());
            next.put(report.key(), new Pool(pool.peakInstances(), pool.allowancePerInstance(), pool.instances(), processed));
            save(new TrafficState(state.month(), next));
            LOG.info("Sent Lightsail traffic alert for {} at {}%", report.key(), highest);
        }
    }

    private void sendStartupStatus(Instant time, CycleResult result) throws InterruptedException {
        if (telegram == null) return;
        String message = startupMessage(time, result);
        try {
            if (telegram.sendHtml(message)) {
                LOG.info("Sent Lightsail traffic startup status");
            } else {
                LOG.warn("Lightsail traffic startup status skipped during Telegram rate limit");
            }
        } catch (IOException e) {
            LOG.warn("Lightsail traffic startup status failed: {}", e.getMessage());
        }
    }

    private String startupMessage(Instant time, CycleResult result) {
        var number = trafficNumbers();
        var sections = new ArrayList<String>();
        if (result == null) {
            if (config.checkOnStartup()) {
                sections.add("Traffic data is unavailable at startup.");
            } else {
                int minutes = config.checkIntervalMinutes();
                sections.add("First traffic check in " + number.format(minutes) + (minutes == 1 ? " minute." : " minutes."));
            }
            sections.add("🌍 <b>Monitored regions</b> — " + escapeHtml(String.join(", ", config.regions())));
        } else {
            for (String region : config.regions()) {
                if (!result.checkedRegions().contains(region)) {
                    sections.add("🌍 <b>" + escapeHtml(region) + "</b> — Traffic data unavailable");
                    continue;
                }
                var groups = result.reports().stream().filter(report -> region.equals(report.region())).toList();
                if (groups.isEmpty()) {
                    sections.add("🌍 <b>" + escapeHtml(region) + "</b> — No instances");
                    continue;
                }
                for (Report report : groups) {
                    sections.add(reportDetails(report, region, number));
                }
            }
        }
        var status = new StringBuilder("🟢 <b>AWSCleaner started</b>\nLightsail traffic status\n⏰ ")
                .append(TIMESTAMP.format(time));
        for (String section : sections) {
            if (status.length() + section.length() + 2 > STARTUP_DETAILS_LIMIT) {
                status.append("\n\n… See the application log for the remaining regions and bundles.");
                break;
            }
            status.append("\n\n").append(section);
        }
        if (result != null) status.append("\n\nℹ️ Usage and allowances are estimates.");
        return status.toString();
    }

    private String regionName(String region) throws InterruptedException {
        if (regionNames == null) {
            var names = new HashMap<String, String>();
            try {
                for (var item : clients.get(region).getRegions()) {
                    if (item.name() != null && item.displayName() != null && !item.displayName().isBlank()) {
                        names.put(item.name(), item.displayName());
                    }
                }
            } catch (IOException e) {
                LOG.warn("Lightsail region display names unavailable: {}", e.getMessage());
            }
            regionNames = names;
        }
        String name = regionNames.get(region);
        return name == null ? region : name + " (" + region + ")";
    }

    private static String message(Report report, String regionName, int level, Instant end) {
        String icon = level >= CRITICAL_ICON_PERCENTAGE ? "🔴" : "⚠️";
        return """
                %s <b>Amazon Lightsail Traffic Warning</b>

                %s

                ⏰ Checked at %s
                ℹ️ Usage and allowances are estimates.
                """.formatted(icon, reportDetails(report, regionName, trafficNumbers()), TIMESTAMP.format(end)).stripTrailing();
    }

    private static String reportDetails(Report report, String region, NumberFormat number) {
        String identity = """
                🌍 <b>Region</b> — %s
                📦 <b>Bundle</b> — %s
                🖥 <b>Active instances</b> — %s
                """.formatted(escapeHtml(region), escapeHtml(report.plans()), number.format(report.active()));
        Double percent = report.percent();
        if (percent == null) return identity + "⚠️ Incomplete usage data";
        String usage = """
                ────────────────────
                📊 <b>Total allowance</b> — %s
                📥 <b>Inbound traffic</b> — %s
                📤 <b>Outbound traffic</b> — %s
                🔸 <b>Traffic used</b> — %s
                🔹 <b>Traffic remaining</b> — %s

                %s
                📈 <b>Allowance used — %s%%</b>""".formatted(volume(number, report.allowance()),
                volume(number, report.totals().incoming()), volume(number, report.totals().outgoing()),
                volume(number, report.totals().consumed()), volume(number, report.remaining()),
                usageProgress(percent), number.format(percent));
        return identity + usage + (report.retired() == 0 ? "" : "\nTotals include the last readings from deleted instances.");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String usageProgress(double percent) {
        int segments = 10;
        int filled = (int) (Math.min(percent, 100) * segments / 100);
        return "🟨".repeat(filled) + "⬛".repeat(segments - filled);
    }

    private static String volume(NumberFormat number, double bytes) {
        if (bytes >= TIB) return number.format(bytes / TIB) + " TiB";
        if (bytes >= GIB) return number.format(bytes / GIB) + " GiB";
        return number.format(bytes / (1L << 20)) + " MiB";
    }

    private static NumberFormat trafficNumbers() {
        var number = NumberFormat.getNumberInstance(Locale.US);
        number.setMaximumFractionDigits(2);
        return number;
    }

    private static void log(Report report) {
        if (report.percent() == null) {
            LOG.warn("Lightsail traffic estimate incomplete for {}: active={}, retired={}, missing={}, "
                            + "allowanceBytes={}, knownTotals={}", report.key(), report.active(), report.retired(),
                    report.missing(), report.allowance(), report.totals());
            return;
        }
        LOG.info("Lightsail traffic estimate for {}: active={}, peak={}, retired={}, allowanceBytes={}, "
                        + "networkInBytes={}, networkOutBytes={}, consumedBytes={}, remainingBytes={}, usedPercent={}",
                report.key(), report.active(), report.peak(), report.retired(), report.allowance(), report.totals().incoming(),
                report.totals().outgoing(), report.totals().consumed(), report.remaining(),
                String.format(Locale.ROOT, "%.2f", report.percent()));
    }

    private void loadMonth(YearMonth month) throws IOException {
        if (state == null) {
            var stored = journal.trafficState();
            if (stored != null) {
                try {
                    state = GSON.fromJson(stored, TrafficState.class);
                    if (state == null) throw new IllegalArgumentException();
                } catch (RuntimeException e) {
                    throw new IOException("Invalid traffic monitor state");
                }
            }
        }
        if (state == null || !state.month().equals(month.toString())) {
            dirty = state != null;
            state = new TrafficState(month.toString(), Map.of());
        }
    }

    private void save(TrafficState updated) throws IOException {
        state = updated;
        dirty = true;
        journal.saveTraffic(GSON.toJsonTree(updated).getAsJsonObject());
        dirty = false;
    }

    private static YearMonth utcMonth(Instant instant) {
        return YearMonth.from(instant.atOffset(ZoneOffset.UTC));
    }

    private static String allowanceBundle(String bundle) {
        return bundle.replace("_ipv6_", "_");
    }

    private record Catalog(Instant expiresAt, Map<String, Integer> sizes) {}
    private record CycleResult(List<Report> reports, Set<String> checkedRegions) {}
    private record Measurement(Pool pool, Report report) {}
    private record Report(String key, String region, String plans, int active, int peak, int retired, int missing,
                          Double allowance, Totals totals) {
        Double percent() {
            return allowance == null || missing != 0 || totals == null ? null : totals.consumed() / allowance * 100;
        }

        double remaining() {
            return Math.max(0, allowance - totals.consumed());
        }
    }
}
