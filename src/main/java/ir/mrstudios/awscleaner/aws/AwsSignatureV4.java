package ir.mrstudios.awscleaner.aws;

import ir.mrstudios.awscleaner.config.AppConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class AwsSignatureV4 {
    public static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private AwsSignatureV4() {}

    static Map<String, String> lightsail(AppConfig.Aws config, String host, String action, byte[] payload, Instant now) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("host", host);
        headers.put("content-type", CONTENT_TYPE);
        headers.put("x-amz-target", "Lightsail_20161128." + action);
        headers.put("x-amz-date", TIMESTAMP.format(now));
        if (!config.sessionToken().isEmpty()) headers.put("x-amz-security-token", config.sessionToken());
        headers.put("Authorization", authorization(config.accessKeyId(), config.secretAccessKey(), config.region(),
                "lightsail", "POST", "/", "", headers, payload));
        return headers;
    }

    static String authorization(String accessKey, String secret, String region, String service, String method,
                                String path, String query, Map<String, String> headers, byte[] payload) {
        var sorted = new TreeMap<String, String>();
        headers.forEach((key, value) -> sorted.put(key.toLowerCase(Locale.ROOT), value.strip().replaceAll("[ \\t]+", " ")));
        String signedHeaders = String.join(";", sorted.keySet());
        var canonicalHeaders = new StringBuilder();
        sorted.forEach((key, value) -> canonicalHeaders.append(key).append(':').append(value).append('\n'));
        String canonicalRequest = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + sha256(payload);
        String timestamp = sorted.get("x-amz-date");
        String date = timestamp.substring(0, 8);
        String scope = String.join("/", date, region, service, "aws4_request");
        String toSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n" + sha256(bytes(canonicalRequest));
        byte[] dateKey = hmac(bytes("AWS4" + secret), date);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        byte[] signingKey = hmac(serviceKey, "aws4_request");
        return ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + HexFormat.of().formatHex(hmac(signingKey, toSign));
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes(value));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
