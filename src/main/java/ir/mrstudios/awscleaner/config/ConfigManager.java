package ir.mrstudios.awscleaner.config;

import okhttp3.HttpUrl;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ConfigManager {
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final String REGION = "[a-z]{2}(?:-[a-z0-9]+)+-\\d+";
    private static final List<String> PROBES = List.of(
            "https://www.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace");

    private ConfigManager() {}

    public static boolean initializeIfMissing(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(target)) return false;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a configuration file at " + target);
        }

        Files.createDirectories(target.getParent());
        try (var template = ConfigManager.class.getResourceAsStream("/config.yml")) {
            if (template == null) throw new IOException("The bundled config.yml resource is missing");
            try {
                Files.copy(template, target);
                return true;
            } catch (FileAlreadyExistsException e) {
                if (Files.isRegularFile(target)) return false;
                throw e;
            }
        }
    }

    public static AppConfig load(Path path) throws IOException {
        return load(path, null);
    }

    public static AppConfig load(Path path, Consumer<String> trafficIssue) throws IOException {
        try (Reader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(input, System.getenv(), trafficIssue);
        }
    }

    static AppConfig load(Reader input, Map<String, String> env) {
        return load(input, env, null);
    }

    static AppConfig load(Reader input, Map<String, String> env, Consumer<String> trafficIssue) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(15);
        options.setCodePointLimit(256_000);
        try {
            return parse(new Node(new Yaml(new SafeConstructor(options)).load(input), "", env), trafficIssue);
        } catch (MarkedYAMLException e) {
            String line = e.getProblemMark() == null ? "" : " at line " + (e.getProblemMark().getLine() + 1);
            throw new IllegalArgumentException("Invalid YAML" + line);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Invalid YAML");
        }
    }

    private static AppConfig parse(Node root, Consumer<String> trafficIssue) {
        root.only("application", "aws", "ssh", "ip-rotation", "arvan", "logging", "network-check", "traffic-monitor");
        Node app = root.child("application");
        app.only("check-interval-minutes", "check-on-startup", "dry-run", "state-file");
        var application = new AppConfig.Application(app.number("check-interval-minutes", 5, 1, 1440),
                app.flag("check-on-startup", true), app.flag("dry-run", false),
                app.path("state-file", "state/rotation.json"));

        Node aws = root.child("aws");
        aws.only("access-key-id", "secret-access-key", "session-token", "region", "instance-name",
                "timeout-seconds", "operation-timeout-seconds", "proxy");
        Node proxy = aws.child("proxy");
        proxy.only("host", "port", "username", "password");
        String user = proxy.string("username", "");
        String password = proxy.string("password", "");
        if (user.isEmpty() != password.isEmpty() || user.getBytes(StandardCharsets.UTF_8).length > 255
                || password.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw proxy.invalid("username/password", "provide both SOCKS5 credentials, each at most 255 UTF-8 bytes");
        }
        var proxyConfig = new AppConfig.Proxy(proxy.host("host"),
                proxy.number("port", 1080, 1, 65535), user, password);
        int awsTimeout = aws.number("timeout-seconds", 30, 1, 300);
        int operationTimeout = aws.number("operation-timeout-seconds", 180, 1, 3600);
        if (operationTimeout < awsTimeout) {
            throw aws.invalid("operation-timeout-seconds", "must be at least timeout-seconds");
        }
        var awsConfig = new AppConfig.Aws(aws.match("access-key-id", "[A-Za-z0-9]+"),
                aws.match("secret-access-key", "[!-~]+"), aws.header("session-token", ""),
                aws.match("region", REGION),
                aws.match("instance-name", "[A-Za-z0-9_][A-Za-z0-9_-]*[A-Za-z0-9_]"),
                awsTimeout, operationTimeout, proxyConfig);

        Node ssh = root.child("ssh");
        ssh.only("port", "timeout-seconds", "attempts", "retry-delay-seconds", "host-key-fingerprints");
        List<String> fingerprints = ssh.strings("host-key-fingerprints", List.of());
        if (fingerprints.stream().anyMatch(fp -> !fp.matches("SHA256:[A-Za-z0-9+/]{43}"))) {
            throw ssh.invalid("host-key-fingerprints", "expected OpenSSH SHA256 fingerprints");
        }
        var sshConfig = new AppConfig.Ssh(ssh.number("port", 22, 1, 65535),
                ssh.number("timeout-seconds", 10, 1, 300), ssh.number("attempts", 3, 1, 20),
                ssh.number("retry-delay-seconds", 5, 0, 600), fingerprints);

        Node rotation = root.child("ip-rotation");
        rotation.only("max-attempts", "retry-delay-seconds", "cooldown-minutes", "max-rotations-per-hour",
                "release-old-ip", "static-ip-quota");
        var rotationConfig = new AppConfig.Rotation(rotation.number("max-attempts", 5, 1, 50),
                rotation.number("retry-delay-seconds", 15, 0, 600), rotation.number("cooldown-minutes", 5, 0, 1440),
                rotation.number("max-rotations-per-hour", 10, 1, 100),
                rotation.flag("release-old-ip", true), rotation.number("static-ip-quota", 5, 1, 1000));

        Node arvan = root.child("arvan");
        arvan.only("api-key", "timeout-seconds", "max-retries", "retry-delay-seconds", "records");
        String apiKey = arvan.header("api-key", null);
        if (apiKey.regionMatches(true, 0, "Apikey ", 0, 7)) {
            apiKey = apiKey.substring(7);
        }
        if (!apiKey.matches("[!-~]+")) {
            throw arvan.invalid("api-key", "expected a non-empty API key");
        }
        var arvanConfig = new AppConfig.Arvan(apiKey, arvan.number("timeout-seconds", 30, 1, 300),
                arvan.number("max-retries", 3, 0, 10), arvan.number("retry-delay-seconds", 5, 0, 60), targets(arvan));

        Node logging = root.child("logging");
        logging.only("level", "file");
        String level = logging.string("level", "INFO").toUpperCase(Locale.ROOT);
        if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF").contains(level)) {
            throw logging.invalid("level", "invalid log level");
        }
        var loggingConfig = new AppConfig.Logging(level, logging.path("file", "logs/application.log"));
        if (loggingConfig.file().equals(application.stateFile())
                || loggingConfig.file().equals(application.stateFile().resolveSibling(application.stateFile().getFileName() + ".lock"))) {
            throw logging.invalid("file", "must be separate from the rotation state and lock");
        }

        Node network = root.optionalChild("network-check");
        network.only("timeout-seconds", "urls");
        List<HttpUrl> urls = new ArrayList<>();
        for (String value : network.strings("urls", PROBES)) {
            HttpUrl url = HttpUrl.parse(value);
            if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                    || url.query() != null || url.fragment() != null) {
                throw network.invalid("urls", "expected HTTPS URLs without credentials, query, or fragment");
            }
            urls.add(url);
        }
        if (urls.isEmpty()) {
            throw network.invalid("urls", "at least one direct network probe is required");
        }
        return new AppConfig(application, awsConfig, sshConfig, rotationConfig, arvanConfig, loggingConfig,
                new AppConfig.NetworkCheck(network.number("timeout-seconds", 5, 1, 60), List.copyOf(urls)),
                trafficMonitor(root, awsConfig.region(), trafficIssue));
    }

    private static AppConfig.TrafficMonitor trafficMonitor(Node root, String defaultRegion, Consumer<String> issue) {
        var disabled = AppConfig.Telegram.disabled();
        try {
            var node = root.optionalChild("traffic-monitor");
            node.only("enabled", "check-on-startup", "check-interval-minutes", "regions", "warning-percentages", "telegram");
            boolean enabled = node.flag("enabled", false);
            var regions = new LinkedHashSet<>(node.strings("regions", List.of(defaultRegion)));
            if (regions.isEmpty() || regions.stream().anyMatch(region -> region.length() > 63 || !region.matches(REGION))) {
                throw node.invalid("regions", "requires at least one AWS Region code");
            }
            var percentages = new TreeSet<Integer>();
            String field = "warning-percentages";
            for (Object value : node.list(field, List.of())) {
                long percent = node.integer(field, value);
                if (percent < 1 || percent > 100) throw node.invalid(field, "requires percentages between 1 and 100");
                percentages.add((int) percent);
            }
            AppConfig.Telegram telegram = disabled;
            if (enabled) {
                try {
                    var target = node.optionalChild("telegram");
                    target.only("enabled", "bot-token", "chat-id");
                    if (target.flag("enabled", false)) {
                        if (percentages.isEmpty()) throw node.invalid(field, "requires at least one alert threshold");
                        String token = target.match("bot-token", "[0-9]+:[A-Za-z0-9_-]+");
                        long chatId = target.integer("chat-id", target.values.get("chat-id"));
                        if (chatId == 0) throw target.invalid("chat-id", "must be nonzero");
                        telegram = new AppConfig.Telegram(true, token, chatId);
                    }
                } catch (IllegalArgumentException e) {
                    if (issue == null) throw e;
                    issue.accept(e.getMessage() + " (Telegram alerts disabled)");
                }
            }
            return new AppConfig.TrafficMonitor(enabled, node.flag("check-on-startup", true),
                    node.number("check-interval-minutes", 10, 1, 1440),
                    List.copyOf(regions), List.copyOf(percentages), telegram);
        } catch (IllegalArgumentException e) {
            if (issue == null) throw e;
            issue.accept(e.getMessage() + " (traffic monitor disabled)");
            return AppConfig.TrafficMonitor.disabled();
        }
    }

    private static List<AppConfig.DnsTarget> targets(Node arvan) {
        var targets = new LinkedHashSet<AppConfig.DnsTarget>();
        List<?> records = arvan.list("records", null);
        for (int i = 0; i < records.size(); i++) {
            Node record = new Node(records.get(i), "arvan.records[" + i + "]", arvan.env);
            record.only("domain", "subdomain");
            String domain = record.string("domain", null);
            String subdomain = record.string("subdomain", null);
            try {
                domain = dnsName(domain);
                if (!domain.contains(".")) {
                    throw new IllegalArgumentException();
                }
                if (!subdomain.equals("@") && !subdomain.equals("*")) {
                    subdomain = dnsName(subdomain);
                    if (subdomain.equals(domain)) {
                        subdomain = "@";
                    } else if (subdomain.endsWith("." + domain)) {
                        subdomain = subdomain.substring(0, subdomain.length() - domain.length() - 1);
                    }
                }
            } catch (IllegalArgumentException e) {
                throw record.invalid("domain/subdomain", "invalid DNS name");
            }
            var target = new AppConfig.DnsTarget(domain, subdomain);
            if (target.fqdn().length() > 253 || !targets.add(target)) {
                throw record.invalid("subdomain", "DNS name is too long or repeated");
            }
        }
        if (targets.isEmpty()) {
            throw arvan.invalid("records", "at least one DNS record is required");
        }
        return List.copyOf(targets);
    }

    private static String dnsName(String name) {
        String absolute = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        return absolute.endsWith(".") ? absolute.substring(0, absolute.length() - 1) : absolute;
    }

    private static final class Node {
        private final Map<?, ?> values;
        private final String path;
        private final Map<String, String> env;

        Node(Object values, String path, Map<String, String> env) {
            this.path = path;
            this.env = env;
            if (!(values instanceof Map<?, ?> map)) {
                throw invalid("", "expected a mapping");
            }
            this.values = map;
        }

        Node child(String field) {
            return new Node(values.get(field), name(field), env);
        }

        Node optionalChild(String field) {
            return values.containsKey(field) ? child(field) : new Node(Map.of(), name(field), env);
        }

        void only(String... fields) {
            if (!Set.of(fields).containsAll(values.keySet())) {
                throw invalid("", "contains an unknown setting");
            }
        }

        String name(String field) {
            if (path.isEmpty()) return field.isEmpty() ? "config.yml" : field;
            return field.isEmpty() ? path : path + "." + field;
        }

        IllegalArgumentException invalid(String field, String explanation) {
            return new IllegalArgumentException(name(field) + " " + explanation);
        }

        String substitute(String field, String text) {
            var result = new StringBuilder();
            var matcher = ENV.matcher(text);
            while (matcher.find()) {
                String replacement = env.get(matcher.group(1));
                if (replacement == null) {
                    throw invalid(field, "requires environment variable " + matcher.group(1));
                }
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        String string(String field, String fallback) {
            if (!values.containsKey(field) && fallback != null) return fallback;
            if (!(values.get(field) instanceof String text)) throw invalid(field, "requires a string");
            String resolved = substitute(field, text);
            if (fallback == null && resolved.isBlank()) throw invalid(field, "must not be empty");
            return resolved;
        }

        String match(String field, String regex) {
            String value = string(field, null);
            if (!value.matches(regex)) throw invalid(field, "has invalid format");
            return value;
        }

        String header(String field, String fallback) {
            String value = string(field, fallback);
            if (!value.matches("[ -~]*")) throw invalid(field, "contains invalid header characters");
            return value;
        }

        String host(String field) {
            String value = string(field, null);
            try {
                return new HttpUrl.Builder().scheme("http").host(value).build().host();
            } catch (IllegalArgumentException e) {
                throw invalid(field, "requires a valid hostname or IP address without a port");
            }
        }

        int number(String field, int fallback, int min, int max) {
            if (!values.containsKey(field)) return fallback;
            Object value = values.get(field);
            try {
                int n;
                if (value instanceof Integer number) {
                    n = number;
                } else if (value instanceof String text) {
                    n = Integer.parseInt(substitute(field, text));
                } else {
                    throw new NumberFormatException();
                }
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException ignored) {
            }
            throw invalid(field, "must be an integer between " + min + " and " + max);
        }

        long integer(String field, Object value) {
            try {
                if (value instanceof Integer n) return n.longValue();
                if (value instanceof Long n) return n;
                if (value instanceof BigInteger n) return n.longValueExact();
                if (value instanceof String text) return Long.parseLong(substitute(field, text));
            } catch (NumberFormatException | ArithmeticException ignored) {
            }
            throw invalid(field, "requires a signed 64-bit integer");
        }

        boolean flag(String field, boolean fallback) {
            if (!values.containsKey(field)) return fallback;
            Object value = values.get(field);
            if (value instanceof Boolean flag) return flag;
            if (value instanceof String text) {
                String expanded = substitute(field, text);
                if (expanded.equalsIgnoreCase("true")) return true;
                if (expanded.equalsIgnoreCase("false")) return false;
            }
            throw invalid(field, "must be true or false");
        }

        Path path(String field, String fallback) {
            String value = string(field, fallback);
            try {
                if (!value.isBlank()) return Path.of(value).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
            }
            throw invalid(field, "requires a valid file path");
        }

        List<?> list(String field, List<?> fallback) {
            if (!values.containsKey(field) && fallback != null) return fallback;
            if (values.get(field) instanceof List<?> list) return list;
            throw invalid(field, "requires a list");
        }

        List<String> strings(String field, List<String> fallback) {
            var result = new ArrayList<String>();
            for (Object item : list(field, fallback)) {
                if (!(item instanceof String text)) throw invalid(field, "requires a list of strings");
                result.add(substitute(field, text));
            }
            return List.copyOf(result);
        }
    }
}
