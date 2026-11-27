package ir.mrstudios.awscleaner.network;

import ir.mrstudios.awscleaner.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class NetworkProbe {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkProbe.class);
    private final OkHttpClient http;
    private final AppConfig.NetworkCheck config;

    public NetworkProbe(OkHttpClient http, AppConfig.NetworkCheck config) {
        this.http = http;
        this.config = config;
    }

    public boolean reachable() throws InterruptedException {
        for (var url : config.urls()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try (var response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
                if (response.isSuccessful()) return true;
                LOG.debug("Direct network probe at {} returned HTTP {}", url.host(), response.code());
            } catch (IOException e) {
                LOG.debug("Direct network probe at {} failed ({})", url.host(), e.getClass().getSimpleName());
            }
        }
        return false;
    }
}
