package ir.mrstudios.awscleaner.service;

import ir.mrstudios.awscleaner.aws.StaticIpManager;
import ir.mrstudios.awscleaner.aws.StaticIpManager.Inventory;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;
import ir.mrstudios.awscleaner.model.RotationState.Stage;
import ir.mrstudios.awscleaner.model.RotationState.Trade;
import ir.mrstudios.awscleaner.network.NetworkProbe;
import ir.mrstudios.awscleaner.ssh.SshChecker;
import ir.mrstudios.awscleaner.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IpRotationService {
    private static final Logger LOG = LoggerFactory.getLogger(IpRotationService.class);
    private final AppConfig config;
    private final StaticIpManager ips;
    private final RotationJournal journal;
    private final SshChecker ssh;
    private final NetworkProbe network;
    private final Clock clock;

    public IpRotationService(AppConfig config, StaticIpManager ips, RotationJournal journal,
                             SshChecker ssh, NetworkProbe network, Clock clock) {
        this.config = config;
        this.ips = ips;
        this.journal = journal;
        this.ssh = ssh;
        this.network = network;
        this.clock = clock;
    }

    public String recover() throws IOException, InterruptedException {
        if (config.application().dryRun()) return null;
        if (journal.state().pending() != null) {
            LOG.info("Reconciling interrupted Lightsail operation {}", journal.state().pending().action().target());
            try {
                ips.awaitPending();
            } catch (StaticIpManager.OperationFailedException e) {
                LOG.warn("Recovered failed Lightsail operation: {}", e.getMessage());
            }
        }
        Trade trade = journal.state().trade();
        if (trade == null) return null;
        LOG.info("Recovering interrupted rotation for {}", trade.candidate());
        Inventory current = ips.inventory();
        if (trade.stage() == Stage.SWAPPING && isCandidate(current.attached(), trade)) {
            if (ssh.reachable(current.attached().ipAddress()) && stillAssigned(current.attached())) {
                return commit(current.attached());
            }
            if (!network.reachable()) throw new IOException("Direct network probes failed; pending rotation will be reviewed later");
        }
        rollback();
        journal.extendCooldown(nextCooldown());
        return null;
    }

    public String rotate(String failedIp) throws IOException, InterruptedException {
        if (clock.millis() < journal.state().cooldownUntil()) {
            LOG.info("Rotation is paused for its configured cooldown");
            return null;
        }
        boolean started = false;
        String expectedIp = failedIp;
        try {
            for (int attempt = 1; attempt <= config.rotation().maxAttempts(); attempt++) {
                if (journal.recentAttempts(clock.millis()).size() >= config.rotation().maxRotationsPerHour()) {
                    LOG.warn("Hourly rotation limit reached");
                    return null;
                }
                if (!network.reachable()) {
                    LOG.warn("Direct HTTPS network probes failed; postponing IP rotation");
                    return null;
                }
                if (!ips.running()) {
                    LOG.info("Lightsail instance is no longer running; postponing rotation");
                    return null;
                }
                Inventory baseline = ips.inventory();
                if (!expectedIp.equals(baseline.instance().publicIpAddress())) {
                    LOG.info("Instance IP changed during the health check; deferring rotation");
                    return null;
                }
                if (!config.application().dryRun()) {
                    clean(false);
                    baseline = ips.inventory();
                    if (!expectedIp.equals(baseline.instance().publicIpAddress())) return null;
                }
                if (baseline.count() >= config.rotation().staticIpQuota()) {
                    LOG.warn("Static IP quota reached ({}/{})", baseline.count(), config.rotation().staticIpQuota());
                    return null;
                }
                if (config.application().dryRun()) {
                    LOG.info("Dry run: would rotate {} after SSH and network checks", failedIp);
                    return null;
                }

                String candidate = "ipm-" + UUID.randomUUID();
                journal.begin(new Trade(expectedIp, baseline.attached(), candidate, Stage.PREPARING),
                        clock.millis(), nextCooldown());
                started = true;
                LOG.info("Starting IP rotation attempt {}/{}", attempt, config.rotation().maxAttempts());
                try {
                    StaticIp allocated = ips.allocate(candidate);
                    LOG.info("Allocated new IP {} ({})", allocated.ipAddress(), candidate);
                    Inventory current = ips.inventory();
                    if (!same(baseline.attached(), current.attached()) || !expectedIp.equals(current.instance().publicIpAddress())
                            || !ips.running()) {
                        throw new IOException("Instance state or IP changed before the swap");
                    }
                    journal.stage(Stage.SWAPPING);
                    if (baseline.attached() != null) {
                        ips.detach(baseline.attached());
                        ips.awaitAssignment(null);
                    }
                    ips.attach(allocated);
                    ips.awaitAssignment(allocated);
                    LOG.info("Attached and confirmed new IP {}", allocated.ipAddress());
                    if (ssh.reachable(allocated.ipAddress())) {
                        if (!stillAssigned(allocated)) throw new IOException("Attached IP changed during SSH verification");
                        return commit(allocated);
                    }
                    LOG.warn("New IP {} failed SSH verification", allocated.ipAddress());
                } catch (IOException e) {
                    if (journal.state().pending() == null && journal.state().trade() != null && ips.running()) rollback();
                    throw e;
                }
                if (!network.reachable()) {
                    LOG.warn("Direct network probes failed; postponing rollback and DNS changes");
                    return null;
                }
                if (!ips.running()) return null;
                rollback();
                Inventory restored = ips.inventory();
                if (!same(baseline.attached(), restored.attached())) return null;
                String restoredIp = restored.instance().publicIpAddress();
                if (!Ipv4.valid(restoredIp)) return null;
                if (!expectedIp.equals(restoredIp) && ssh.reachable(restoredIp)) return restoredIp;
                expectedIp = restoredIp;
                if (attempt < config.rotation().maxAttempts()) {
                    TimeUnit.SECONDS.sleep(config.rotation().retryDelaySeconds());
                }
            }
            LOG.warn("Rotation attempts exhausted; waiting for the next health check");
            return null;
        } finally {
            if (started) journal.extendCooldown(nextCooldown());
        }
    }

    public void clean(boolean dnsComplete) throws InterruptedException {
        if (config.application().dryRun() || !config.rotation().releaseOldIp() || journal.state().pending() != null
                || journal.state().trade() != null) return;
        for (var entry : journal.state().owned().entrySet()) {
            if (!dnsComplete && !journal.state().discarded().contains(entry.getKey())) continue;
            try {
                ips.releaseOwned(entry.getKey(), entry.getValue());
            } catch (IOException e) {
                LOG.warn("Cleanup pending for {}: {}", entry.getKey(), e.getMessage());
                if (journal.state().pending() != null) break;
            }
        }
    }

    private String commit(StaticIp active) throws IOException {
        journal.finish(false);
        LOG.info("IP rotation complete; {} passed SSH verification", active.ipAddress());
        return active.ipAddress();
    }

    private boolean stillAssigned(StaticIp expected) throws IOException, InterruptedException {
        return same(expected, ips.inventory().attached()) && ips.running();
    }

    private void rollback() throws IOException, InterruptedException {
        Trade trade = journal.state().trade();
        if (trade.stage() == Stage.PREPARING) {
            journal.finish(true);
            clean(false);
            return;
        }
        Inventory live = ips.inventory();
        StaticIp original = trade.originalStatic();
        StaticIp attached = live.attached();
        if (same(original, attached)) {
            journal.finish(true);
            clean(false);
            return;
        }
        if (attached != null && !isCandidate(attached, trade)) {
            LOG.warn("Instance has a different static IP; leaving its current attachment in place");
            journal.finish(true);
            clean(false);
            return;
        }

        if (original != null) {
            var available = ips.find(original.name()).filter(ip -> same(ip, original) && !ip.isAttached());
            if (available.isEmpty()) {
                LOG.warn("Previous static IP {} is no longer available for rollback", original.name());
                journal.finish(true);
                clean(false);
                return;
            }
        }
        if (!ips.running()) throw new IOException("Lightsail instance is not running; rollback is deferred");
        journal.stage(Stage.ROLLING_BACK);
        if (attached != null) {
            ips.detach(attached);
            ips.awaitAssignment(null);
        }
        if (original != null) {
            ips.attach(original);
            ips.awaitAssignment(original);
            LOG.info("Restored previous static IP {}", original.ipAddress());
        } else {
            LOG.info("Restored Lightsail's dynamic public IP assignment (previous address {})", trade.originalIp());
        }
        journal.finish(true);
        clean(false);
    }

    private boolean isCandidate(StaticIp attached, Trade trade) {
        return attached != null && trade.candidate().equals(attached.name())
                && attached.arn().equals(journal.state().owned().get(trade.candidate()));
    }

    private static boolean same(StaticIp left, StaticIp right) {
        return left == null || right == null ? left == right : Objects.equals(left.arn(), right.arn());
    }

    private long nextCooldown() {
        return clock.millis() + Duration.ofMinutes(config.rotation().cooldownMinutes()).toMillis();
    }
}
