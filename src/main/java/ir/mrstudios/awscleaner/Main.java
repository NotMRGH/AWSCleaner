package ir.mrstudios.awscleaner;

import ch.qos.logback.classic.LoggerContext;
import ir.mrstudios.awscleaner.arvan.ArvanClient;
import ir.mrstudios.awscleaner.arvan.DnsRecordManager;
import ir.mrstudios.awscleaner.aws.LightsailClient;
import ir.mrstudios.awscleaner.aws.StaticIpManager;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.config.ConfigManager;
import ir.mrstudios.awscleaner.network.HttpClientFactory;
import ir.mrstudios.awscleaner.network.NetworkProbe;
import ir.mrstudios.awscleaner.scheduler.HealthCheckScheduler;
import ir.mrstudios.awscleaner.scheduler.TrafficMonitorScheduler;
import ir.mrstudios.awscleaner.service.DnsSyncService;
import ir.mrstudios.awscleaner.service.HealthCheckService;
import ir.mrstudios.awscleaner.service.IpRotationService;
import ir.mrstudios.awscleaner.service.RotationJournal;
import ir.mrstudios.awscleaner.service.TrafficMonitorService;
import ir.mrstudios.awscleaner.ssh.SshChecker;
import ir.mrstudios.awscleaner.telegram.TelegramClient;
import okhttp3.OkHttpClient;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            start(args);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Startup failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void start(String[] args) throws IOException {
        Path source = Path.of("config.yml");
        boolean validateOnly = false;
        boolean initializeOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help" -> {
                    System.out.println("Usage: java -jar AWSCleaner.jar [--config FILE] [--init-config | --check-config]");
                    return;
                }
                case "--check-config" -> validateOnly = true;
                case "--init-config" -> initializeOnly = true;
                case "--config" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--config requires a file path");
                    source = Path.of(args[i]);
                }
                default -> throw new IllegalArgumentException("Unknown command-line option; see --help");
            }
        }
        if (initializeOnly && validateOnly) {
            throw new IllegalArgumentException("Use either --init-config or --check-config");
        }
        source = source.toAbsolutePath().normalize();
        boolean created = ConfigManager.initializeIfMissing(source);
        if (created) {
            String message = "Created " + source + ". Open it to configure AWSCleaner.";
            if (validateOnly) throw new IllegalArgumentException(message);
            System.out.println(message);
            return;
        }
        if (initializeOnly) {
            System.out.println("Configuration already exists at " + source);
            return;
        }
        var trafficIssues = new ArrayList<String>();
        AppConfig config = ConfigManager.load(source, trafficIssues::add);
        if (validateOnly) {
            trafficIssues.forEach(System.err::println);
            System.out.println(trafficIssues.isEmpty() ? "Configuration is valid." : "Core configuration is valid.");
            return;
        }
        configureLogging(config.logging());
        var log = LoggerFactory.getLogger(Main.class);
        trafficIssues.forEach(issue -> log.warn("{}", issue));
        var clock = Clock.systemUTC();
        var endpoint = LightsailClient.endpoint(config.aws().region());
        var monitor = config.trafficMonitor();
        var trafficRegions = new HashSet<>(monitor.regions());
        if (monitor.enabled()) monitor.regions().forEach(region -> trafficRegions.add(TrafficMonitorService.catalogRegion(region)));
        var proxyHosts = new HashSet<String>();
        proxyHosts.add(endpoint.host());
        if (monitor.enabled()) trafficRegions.forEach(region -> proxyHosts.add(LightsailClient.endpoint(region).host()));
        if (monitor.telegram().enabled()) proxyHosts.add(TelegramClient.ENDPOINT.host());
        var awsHttp = HttpClientFactory.aws(config.aws(), proxyHosts);
        var arvanHttp = HttpClientFactory.direct(config.arvan().timeoutSeconds());
        var probeHttp = HttpClientFactory.direct(config.networkCheck().timeoutSeconds());
        var aws = new LightsailClient(awsHttp, endpoint, config.aws(), clock);
        var arvan = new ArvanClient(arvanHttp, ArvanClient.ENDPOINT, config.arvan());
        var ssh = new SshChecker(config.ssh());
        var journal = new RotationJournal(config.application().stateFile());
        var ips = new StaticIpManager(aws, journal, config.aws().instanceName(), config.aws().operationTimeoutSeconds(), clock);
        var rotation = new IpRotationService(config, ips, journal, ssh, new NetworkProbe(probeHttp, config.networkCheck()), clock);
        var dns = new DnsSyncService(new DnsRecordManager(arvan, config.arvan()), config.arvan().records(),
                config.application().dryRun());
        var health = new HealthCheckService(aws, ssh, rotation, dns, journal, config.application().dryRun());
        var scheduler = new HealthCheckScheduler(config.application(), health);
        var trafficClients = new HashMap<String, LightsailClient>();
        if (monitor.enabled()) {
            for (String region : trafficRegions) {
                trafficClients.put(region, new LightsailClient(awsHttp, LightsailClient.endpoint(region), config.aws(), region, clock));
            }
        }
        var telegram = monitor.telegram().enabled()
                ? new TelegramClient(awsHttp, TelegramClient.ENDPOINT, monitor.telegram(), clock) : null;
        var traffic = new TrafficMonitorScheduler(monitor,
                new TrafficMonitorService(monitor, trafficClients, telegram, journal, clock));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            traffic.stop();
            closeClients(awsHttp, arvanHttp, probeHttp);
            scheduler.close();
            traffic.close();
            try {
                journal.close();
            } catch (IOException e) {
                log.warn("Could not close the rotation state lock ({})", e.getClass().getSimpleName());
            }
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) context.stop();
        }, "awscleaner-shutdown"));
        log.info("AWSCleaner started for Lightsail instance {} in {} (dry run: {})", config.aws().instanceName(),
                config.aws().region(), config.application().dryRun());
        scheduler.start();
        traffic.start();
    }

    private static void configureLogging(AppConfig.Logging config) throws IOException {
        Files.createDirectories(config.file().getParent());
        try (var ignored = Files.newOutputStream(config.file(), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
        }
        System.setProperty("awscleaner.log.file", config.file().toString());
        System.setProperty("awscleaner.log.level", config.level());
    }

    private static void closeClients(OkHttpClient... clients) {
        for (OkHttpClient client : clients) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
