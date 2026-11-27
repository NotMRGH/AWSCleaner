package ir.mrstudios.awscleaner.config;

import okhttp3.HttpUrl;

import java.nio.file.Path;
import java.util.List;

public record AppConfig(Application application, Aws aws, Ssh ssh, Rotation rotation,
                        Arvan arvan, Logging logging, NetworkCheck networkCheck, TrafficMonitor trafficMonitor) {
    public AppConfig(Application application, Aws aws, Ssh ssh, Rotation rotation, Arvan arvan, Logging logging,
                     NetworkCheck networkCheck) {
        this(application, aws, ssh, rotation, arvan, logging, networkCheck, TrafficMonitor.disabled());
    }

    public record Application(int checkIntervalMinutes, boolean checkOnStartup, boolean dryRun, Path stateFile) {}
    public record Aws(String accessKeyId, String secretAccessKey, String sessionToken, String region,
                      String instanceName, int timeoutSeconds, int operationTimeoutSeconds, Proxy proxy) {}
    public record Proxy(String host, int port, String username, String password) {}
    public record Ssh(int port, int timeoutSeconds, int attempts, int retryDelaySeconds, List<String> fingerprints) {}
    public record Rotation(int maxAttempts, int retryDelaySeconds, int cooldownMinutes,
                           int maxRotationsPerHour, boolean releaseOldIp, int staticIpQuota) {}
    public record Arvan(String apiKey, int timeoutSeconds, int maxRetries,
                        int retryDelaySeconds, List<DnsTarget> records) {}
    public record DnsTarget(String domain, String subdomain) {
        public String fqdn() {
            return subdomain.equals("@") ? domain : subdomain + "." + domain;
        }
    }
    public record Logging(String level, Path file) {}
    public record NetworkCheck(int timeoutSeconds, List<HttpUrl> urls) {}
    public record TrafficMonitor(boolean enabled, boolean checkOnStartup, int checkIntervalMinutes,
                                 List<String> regions, List<Integer> warningPercentages, Telegram telegram) {
        static TrafficMonitor disabled() {
            return new TrafficMonitor(false, true, 10, List.of(), List.of(), Telegram.disabled());
        }
    }
    public record Telegram(boolean enabled, String botToken, long chatId) {
        static Telegram disabled() {
            return new Telegram(false, "", 0);
        }
    }
}
