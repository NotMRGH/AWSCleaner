package ir.mrstudios.awscleaner.network;

import ir.mrstudios.awscleaner.config.AppConfig;
import okhttp3.OkHttpClient;

import java.net.Proxy;
import java.time.Duration;

public final class HttpClientFactory {
    private HttpClientFactory() {}

    public static OkHttpClient aws(AppConfig.Aws config, String host) {
        var socks = new Socks5Proxy(config.proxy(), host, config.timeoutSeconds());
        return builder(config.timeoutSeconds()).socketFactory(socks).dns(socks.remoteDns()).build();
    }

    public static OkHttpClient direct(int timeoutSeconds) {
        return builder(timeoutSeconds).socketFactory(new DirectSocketFactory()).build();
    }

    private static OkHttpClient.Builder builder(int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false);
    }
}
