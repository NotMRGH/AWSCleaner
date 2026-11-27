package ir.mrstudios.awscleaner.arvan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.config.AppConfig.DnsTarget;
import ir.mrstudios.awscleaner.util.HttpJson;
import ir.mrstudios.awscleaner.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DnsRecordManager {
    private static final Logger LOG = LoggerFactory.getLogger(DnsRecordManager.class);
    private static final Set<String> READ_ONLY = Set.of("id", "created_at", "updated_at", "is_protected", "usage");
    private final ArvanClient client;
    private final AppConfig.Arvan config;

    public DnsRecordManager(ArvanClient client, AppConfig.Arvan config) {
        this.client = client;
        this.config = config;
    }

    public List<JsonObject> list(String domain) throws IOException, InterruptedException {
        return client.list(domain);
    }

    public boolean matches(JsonObject record, DnsTarget target) {
        if (!"a".equalsIgnoreCase(HttpJson.string(record, "type"))) return false;
        String name = HttpJson.string(record, "name").toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name.equals(target.subdomain()) || name.equals(target.fqdn());
    }

    public void sync(JsonObject listed, DnsTarget target, String ip, boolean dryRun)
            throws IOException, InterruptedException {
        String id = HttpJson.string(listed, "id");
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("DNS record is missing a valid ID");
        JsonObject record = listed;
        for (int attempt = 0; ; attempt++) {
            if (!matches(record, target) || !id.equals(HttpJson.string(record, "id"))) {
                throw new IOException("DNS record identity changed during reconciliation");
            }
            JsonObject update = change(record, ip);
            if (update == null) {
                LOG.debug("{} already points to {}", target.fqdn(), ip);
                return;
            }
            if (dryRun) {
                LOG.info("Dry run: would update {} to {}", target.fqdn(), ip);
                return;
            }
            if (record.has("is_protected") && record.get("is_protected").isJsonPrimitive()
                    && record.get("is_protected").getAsBoolean()) {
                throw new IOException("DNS record is protected");
            }
            try {
                var response = client.update(target.domain(), id, update);
                JsonObject verified = response.get("data") instanceof JsonObject data
                        ? data : client.record(target.domain(), id);
                if (!matches(verified, target) || !id.equals(HttpJson.string(verified, "id"))
                        || change(verified, ip) != null) {
                    throw new IOException("ArvanCloud did not confirm the requested DNS IP");
                }
                LOG.info("Updated {} to {}", target.fqdn(), ip);
                return;
            } catch (IOException e) {
                if (attempt >= config.maxRetries() || !client.retryable(e)) throw e;
                client.pause(attempt, e);
                record = client.record(target.domain(), id);
            }
        }
    }

    static JsonObject change(JsonObject original, String ip) throws IOException {
        JsonObject body = original.deepCopy();
        if (!(body.get("value") instanceof JsonArray values) || values.isEmpty()) {
            throw new IOException("A record has an invalid IPv4 value list");
        }
        boolean changed = false;
        for (var item : values) {
            if (!(item instanceof JsonObject value) || !Ipv4.valid(HttpJson.string(value, "ip"))) {
                throw new IOException("A record contains an invalid IPv4 origin");
            }
            if (!ip.equals(HttpJson.string(value, "ip"))) {
                value.addProperty("ip", ip);
                changed = true;
            }
            if (value.has("original_weight") && !value.get("original_weight").isJsonNull()) {
                value.add("weight", value.get("original_weight"));
            }
            value.remove("original_weight");
        }
        if (!changed) return null;
        READ_ONLY.forEach(body::remove);
        body.addProperty("type", "a");
        return body;
    }
}
