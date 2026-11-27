package ir.mrstudios.awscleaner.aws;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.Instance;
import ir.mrstudios.awscleaner.model.Lightsail.Operation;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;
import ir.mrstudios.awscleaner.util.ApiException;
import ir.mrstudios.awscleaner.util.HttpJson;
import ir.mrstudios.awscleaner.util.Ipv4;
import ir.mrstudios.awscleaner.util.Retry;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public final class LightsailClient {
    private static final Logger LOG = LoggerFactory.getLogger(LightsailClient.class);
    private static final Gson GSON = new Gson();
    private final OkHttpClient http;
    private final HttpUrl endpoint;
    private final AppConfig.Aws config;
    private final Clock clock;

    public LightsailClient(OkHttpClient http, HttpUrl endpoint, AppConfig.Aws config, Clock clock) {
        this.http = http.newBuilder().addNetworkInterceptor(chain -> {
            var transmission = chain.request().tag(Transmission.class);
            if (transmission != null) transmission.started = true;
            return chain.proceed(chain.request());
        }).build();
        this.endpoint = endpoint;
        this.config = config;
        this.clock = clock;
    }

    public static HttpUrl endpoint(String region) {
        String suffix = region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
        return HttpUrl.get("https://lightsail." + region + "." + suffix + "/");
    }

    public Instance getInstance() throws IOException, InterruptedException {
        return getInstance(0);
    }

    Instance getInstance(long deadline) throws IOException, InterruptedException {
        var result = read("GetInstance", input("instanceName", config.instanceName()), deadline);
        Instance instance = decode(result, "instance", Instance.class);
        if (instance.arn() == null || instance.arn().isBlank()) throw invalid("GetInstance");
        return instance;
    }

    public String getInstanceState() throws IOException, InterruptedException {
        var result = read("GetInstanceState", input("instanceName", config.instanceName()));
        if (!result.has("state") || !result.get("state").isJsonObject()) throw invalid("GetInstanceState");
        String state = HttpJson.string(result.getAsJsonObject("state"), "name");
        if (state.isEmpty()) throw invalid("GetInstanceState");
        return state;
    }

    public List<StaticIp> getStaticIps() throws IOException, InterruptedException {
        return getStaticIps(0);
    }

    List<StaticIp> getStaticIps(long deadline) throws IOException, InterruptedException {
        var ips = paginated("GetStaticIps", new JsonObject(), "staticIps", StaticIp.class, deadline);
        for (StaticIp ip : ips) validateStatic(ip);
        return ips;
    }

    public Optional<StaticIp> getStaticIp(String name) throws IOException, InterruptedException {
        return getStaticIp(name, 0);
    }

    Optional<StaticIp> getStaticIp(String name, long deadline) throws IOException, InterruptedException {
        try {
            var result = read("GetStaticIp", input("staticIpName", name), deadline);
            StaticIp ip = decode(result, "staticIp", StaticIp.class);
            validateStatic(ip);
            if (!name.equals(ip.name())) throw invalid("GetStaticIp");
            return Optional.of(ip);
        } catch (ApiException e) {
            if (e.missing()) return Optional.empty();
            throw e;
        }
    }

    public Operation getOperation(String id, long deadline) throws IOException, InterruptedException {
        Operation operation = decode(read("GetOperation", input("operationId", id), deadline), "operation", Operation.class);
        if (!id.equals(operation.id())) throw invalid("GetOperation");
        return operation;
    }

    public List<Operation> getOperationsForResource(String name, long deadline) throws IOException, InterruptedException {
        try {
            return paginated("GetOperationsForResource", input("resourceName", name), "operations", Operation.class, deadline);
        } catch (ApiException e) {
            if (e.missing()) return List.of();
            throw e;
        }
    }

    public List<Operation> mutate(Action action, String name) throws IOException, InterruptedException {
        JsonObject params = input("staticIpName", name);
        if (action == Action.ATTACH) params.addProperty("instanceName", config.instanceName());
        return list(call(action.target(), params, true, 0), "operations", Operation.class);
    }

    private <T> List<T> paginated(String action, JsonObject params, String key, Class<T> type, long deadline)
            throws IOException, InterruptedException {
        List<T> result = new ArrayList<>();
        var seen = new HashSet<String>();
        String token;
        do {
            var page = read(action, params, deadline);
            if (page.has(key) && !page.get(key).isJsonNull()) result.addAll(list(page, key, type));
            token = HttpJson.string(page, "nextPageToken");
            if (!token.isEmpty() && (!seen.add(token) || seen.size() > 1000)) throw invalid(action + " pagination");
            params.addProperty("pageToken", token);
        } while (!token.isEmpty());
        return result;
    }

    private JsonObject read(String action, JsonObject params) throws IOException, InterruptedException {
        return read(action, params, 0);
    }

    private JsonObject read(String action, JsonObject params, long deadline) throws IOException, InterruptedException {
        for (int retry = 0; ; retry++) {
            try {
                return call(action, params, false, deadline);
            } catch (IOException e) {
                if (deadline != 0 || retry == 2 || (e instanceof ApiException api && !api.retryable())) throw e;
                LOG.debug("Retrying Lightsail {} after {}", action, e.getClass().getSimpleName());
                Retry.pause(1, retry, e instanceof ApiException api ? api.retryAfter() : null);
            }
        }
    }

    private JsonObject call(String action, JsonObject params, boolean mutation, long deadline)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        byte[] bytes = GSON.toJson(params).getBytes(StandardCharsets.UTF_8);
        String host = endpoint.host() + (endpoint.port() == HttpUrl.defaultPort(endpoint.scheme()) ? "" : ":" + endpoint.port());
        Transmission transmission = new Transmission();
        var request = new Request.Builder().url(endpoint).tag(Transmission.class, transmission)
                .post(HttpJson.body(bytes, AwsSignatureV4.CONTENT_TYPE, mutation));
        AwsSignatureV4.lightsail(config, host, action, bytes, clock.instant()).forEach(request::header);
        var call = http.newCall(request.build());
        if (deadline != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Lightsail " + action + " observation timed out");
            call.timeout().timeout(Math.min(remaining, call.timeout().timeoutNanos()), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        try (var response = call.execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new ApiException("Lightsail " + action, response.code(), errorCode(responseBody),
                        response.header("Retry-After"));
            }
            return HttpJson.object(responseBody, "Lightsail " + action);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            if (!transmission.started && mutation) throw new RequestNotSentException(action, e);
            throw new IOException("Lightsail " + action + " failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String errorCode(String body) {
        try {
            var error = HttpJson.object(body, "Lightsail");
            String raw = HttpJson.string(error, "__type");
            if (raw.isEmpty()) raw = HttpJson.string(error, "code");
            return switch (raw.substring(raw.lastIndexOf('#') + 1)) {
                case "NotFoundException", "ThrottlingException", "OperationFailureException", "AccessDeniedException",
                        "AccountSetupInProgressException", "UnauthenticatedException", "InvalidInputException",
                        "ServiceException", "RegionSetupInProgressException", "SignatureDoesNotMatch",
                        "InvalidSignatureException", "ExpiredTokenException", "LimitExceededException",
                        "ServiceQuotaExceededException" -> raw.substring(raw.lastIndexOf('#') + 1);
                default -> "";
            };
        } catch (IOException ignored) {
            return "";
        }
    }

    private static JsonObject input(String key, String value) {
        var result = new JsonObject();
        result.addProperty(key, value);
        return result;
    }

    private static <T> T decode(JsonObject source, String key, Class<T> type) throws IOException {
        try {
            if (source.get(key) == null || !source.get(key).isJsonObject()) throw invalid(key);
            return GSON.fromJson(source.get(key), type);
        } catch (RuntimeException e) {
            throw invalid(key);
        }
    }

    private static <T> List<T> list(JsonObject source, String key, Class<T> type) throws IOException {
        try {
            JsonArray values = source.getAsJsonArray(key);
            if (values == null) throw invalid(key);
            List<T> result = new ArrayList<>();
            for (var value : values) {
                if (!value.isJsonObject()) throw invalid(key);
                result.add(GSON.fromJson(value, type));
            }
            return result;
        } catch (RuntimeException e) {
            throw invalid(key);
        }
    }

    private static IOException invalid(String field) {
        return new IOException("Invalid Lightsail response for " + field);
    }

    private static void validateStatic(StaticIp ip) throws IOException {
        if (ip.name() == null || ip.name().isBlank() || !Ipv4.valid(ip.ipAddress())
                || ip.arn() == null || ip.arn().isBlank()
                || ip.isAttached() != (ip.attachedTo() != null && !ip.attachedTo().isEmpty())) {
            throw invalid("static IP");
        }
    }

    private static final class Transmission {
        private volatile boolean started;
    }

    public static final class RequestNotSentException extends IOException {
        RequestNotSentException(String action, IOException cause) {
            super("Lightsail " + action + " could not connect (" + cause.getClass().getSimpleName() + ")");
        }
    }
}
