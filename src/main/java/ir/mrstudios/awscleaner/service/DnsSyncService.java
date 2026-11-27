package ir.mrstudios.awscleaner.service;

import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.arvan.DnsRecordManager;
import ir.mrstudios.awscleaner.config.AppConfig.DnsTarget;
import ir.mrstudios.awscleaner.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DnsSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(DnsSyncService.class);
    private final DnsRecordManager records;
    private final Map<String, List<DnsTarget>> domains = new LinkedHashMap<>();
    private final boolean dryRun;

    public DnsSyncService(DnsRecordManager records, List<DnsTarget> targets, boolean dryRun) {
        this.records = records;
        this.dryRun = dryRun;
        for (DnsTarget target : targets) domains.computeIfAbsent(target.domain(), ignored -> new ArrayList<>()).add(target);
    }

    public boolean sync(String ip) throws InterruptedException {
        if (!Ipv4.valid(ip)) throw new IllegalArgumentException("DNS synchronization requires a valid IPv4");
        LOG.info("Reconciling configured ArvanCloud A records with {}", ip);
        boolean complete = true;
        for (var domain : domains.entrySet()) {
            List<JsonObject> listed;
            try {
                listed = records.list(domain.getKey());
            } catch (IOException e) {
                LOG.warn("Cannot list DNS records for {}: {}", domain.getKey(), e.getMessage());
                complete = false;
                continue;
            }
            for (DnsTarget target : domain.getValue()) {
                boolean found = false;
                for (JsonObject record : listed) {
                    if (!records.matches(record, target)) continue;
                    found = true;
                    try {
                        records.sync(record, target, ip, dryRun);
                    } catch (IOException e) {
                        LOG.warn("DNS update pending for {}: {}", target.fqdn(), e.getMessage());
                        complete = false;
                    }
                }
                if (!found) {
                    LOG.warn("Configured A record {} was not found", target.fqdn());
                    complete = false;
                }
            }
        }
        return complete;
    }
}
