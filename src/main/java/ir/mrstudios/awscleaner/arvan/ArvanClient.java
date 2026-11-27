package ir.mrstudios.awscleaner.arvan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.util.ApiException;
import ir.mrstudios.awscleaner.util.HttpJson;
import ir.mrstudios.awscleaner.util.Retry;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ArvanClient {
    public static final HttpUrl ENDPOINT = HttpUrl.get("https://napi.arvancloud.ir/cdn/4.0");
    private final OkHttpClient http;
    private final HttpUrl base;
    private final AppConfig.Arvan config;

    public ArvanClient(OkHttpClient http, HttpUrl base, AppConfig.Arvan config) {
        this.http = http;
        this.base = base;
        this.config = config;
    }

    public List<JsonObject> list(String domain) throws IOException, InterruptedException {
        List<JsonObject> records = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            HttpUrl url = recordsUrl(domain).newBuilder().addQueryParameter("page", Integer.toString(page)).build();
            JsonObject response = get(url);
            if (!(response.get("data") instanceof JsonArray rows)) {
                throw new IOException("ArvanCloud returned an invalid DNS list");
            }
            for (var row : rows) {
                if (!row.isJsonObject()) throw new IOException("ArvanCloud returned an invalid DNS record");
                records.add(row.getAsJsonObject());
            }
            if (!hasNextPage(response, page)) return records;
        }
        throw new IOException("ArvanCloud DNS pagination exceeded its limit");
    }

    private static boolean hasNextPage(JsonObject response, int page) throws IOException {
        try {
            if (response.get("meta") instanceof JsonObject meta && meta.has("last_page")) {
                if (meta.has("current_page") && meta.get("current_page").getAsInt() != page) {
                    throw new IOException("ArvanCloud returned the wrong DNS page");
                }
                return page < meta.get("last_page").getAsInt();
            }
            return response.get("links") instanceof JsonObject links && !HttpJson.string(links, "next").isEmpty();
        } catch (RuntimeException e) {
            throw new IOException("ArvanCloud returned invalid pagination");
        }
    }

    public JsonObject record(String domain, String id) throws IOException, InterruptedException {
        var response = get(recordsUrl(domain).newBuilder().addPathSegment(id).build());
        if (response.get("data") instanceof JsonObject record) return record;
        throw new IOException("ArvanCloud returned invalid DNS record data");
    }

    public JsonObject update(String domain, String id, JsonObject record) throws IOException, InterruptedException {
        var request = request(recordsUrl(domain).newBuilder().addPathSegment(id).build())
                .put(HttpJson.body(record.toString().getBytes(StandardCharsets.UTF_8), "application/json", true))
                .build();
        return send(request);
    }

    private JsonObject get(HttpUrl url) throws IOException, InterruptedException {
        for (int retry = 0; ; retry++) {
            try {
                return send(request(url).get().build());
            } catch (IOException e) {
                if (retry >= config.maxRetries() || !retryable(e)) throw e;
                pause(retry, e);
            }
        }
    }

    boolean retryable(IOException e) {
        return !(e instanceof ApiException api) || api.retryable();
    }

    void pause(int attempt, IOException e) throws InterruptedException {
        Retry.pause(config.retryDelaySeconds(), attempt, e instanceof ApiException api ? api.retryAfter() : null);
    }

    private HttpUrl recordsUrl(String domain) {
        return base.newBuilder().addPathSegment("domains").addPathSegment(domain)
                .addPathSegment("dns-records").build();
    }

    private Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("Authorization", "Apikey " + config.apiKey())
                .header("Accept", "application/json");
    }

    private JsonObject send(Request request) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("ArvanCloud " + request.method(), response.code(), "", response.header("Retry-After"));
            }
            if (response.code() == 204) return new JsonObject();
            JsonObject body = HttpJson.object(response.body() == null ? "" : response.body().string(), "ArvanCloud");
            if (body.has("status") && body.get("status").isJsonPrimitive()
                    && body.getAsJsonPrimitive("status").isBoolean() && !body.get("status").getAsBoolean()) {
                throw new IOException("ArvanCloud reported an unsuccessful " + request.method());
            }
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("ArvanCloud " + request.method() + " failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
