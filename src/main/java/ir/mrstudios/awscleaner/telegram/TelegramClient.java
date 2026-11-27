package ir.mrstudios.awscleaner.telegram;

import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.util.HttpJson;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class TelegramClient {
    public static final HttpUrl ENDPOINT = HttpUrl.get("https://api.telegram.org/");
    private static final Duration MESSAGE_SPACING = Duration.ofMillis(3100);
    private final OkHttpClient http;
    private final HttpUrl sendMessage;
    private final long chatId;
    private final Clock clock;
    private Instant resumeAt = Instant.MIN;
    private long lastRequest;

    public TelegramClient(OkHttpClient http, HttpUrl endpoint, AppConfig.Telegram config, Clock clock) {
        this.http = http;
        this.sendMessage = endpoint.newBuilder().addPathSegment("bot" + config.botToken())
                .addPathSegment("sendMessage").build();
        this.chatId = config.chatId();
        this.clock = clock;
    }

    public boolean send(String message) throws IOException, InterruptedException {
        return send(message, null);
    }

    public boolean sendHtml(String message) throws IOException, InterruptedException {
        return send(message, "HTML");
    }

    private boolean send(String message, String parseMode) throws IOException, InterruptedException {
        if (clock.instant().isBefore(resumeAt)) return false;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (lastRequest != 0) {
            long remaining = MESSAGE_SPACING.toNanos() - (System.nanoTime() - lastRequest);
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
        }
        var payload = new JsonObject();
        payload.addProperty("chat_id", chatId);
        payload.addProperty("text", message);
        if (parseMode != null) payload.addProperty("parse_mode", parseMode);
        var request = new Request.Builder().url(sendMessage).post(HttpJson.body(
                payload.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", true)).build();
        lastRequest = System.nanoTime();
        String body;
        String retryAfter;
        int status;
        try (var response = http.newCall(request).execute()) {
            body = response.body() == null ? "" : response.body().string();
            retryAfter = response.header("Retry-After");
            status = response.code();
        } catch (IOException e) {
            throw new IOException("Telegram sendMessage failed (" + e.getClass().getSimpleName() + ")");
        }
        if (status == 429) {
            defer(retryAfter(body, retryAfter));
            throw new IOException("Telegram sendMessage rate limited (HTTP 429)");
        }
        if (status < 200 || status >= 300) throw new IOException("Telegram sendMessage returned HTTP " + status);
        var result = HttpJson.object(body, "Telegram sendMessage");
        var ok = result.get("ok");
        if (ok != null && ok.isJsonPrimitive() && ok.getAsJsonPrimitive().isBoolean() && ok.getAsBoolean()) return true;
        var code = result.get("error_code");
        if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()
                && code.getAsNumber().doubleValue() == 429) defer(retryAfter(result, retryAfter));
        throw new IOException("Telegram sendMessage did not confirm delivery");
    }

    private void defer(long seconds) {
        resumeAt = clock.instant().plusSeconds(seconds);
    }

    private static long retryAfter(String body, String header) {
        try {
            return retryAfter(HttpJson.object(body, "Telegram sendMessage"), header);
        } catch (IOException ignored) {
            return retryAfter(new JsonObject(), header);
        }
    }

    private static long retryAfter(JsonObject result, String header) {
        long seconds = 0;
        try {
            var parameters = result.getAsJsonObject("parameters");
            if (parameters != null && parameters.has("retry_after")) {
                seconds = parameters.get("retry_after").getAsLong();
            }
        } catch (RuntimeException ignored) {
        }
        if (header != null) {
            try {
                seconds = Math.max(seconds, Long.parseLong(header));
            } catch (NumberFormatException ignored) {
            }
        }
        return seconds > 0 ? Math.min(seconds, Integer.MAX_VALUE) : 60;
    }
}
