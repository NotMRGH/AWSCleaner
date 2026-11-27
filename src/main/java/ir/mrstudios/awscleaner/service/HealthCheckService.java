package ir.mrstudios.awscleaner.service;

import ir.mrstudios.awscleaner.aws.LightsailClient;
import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.Instance;
import ir.mrstudios.awscleaner.ssh.SshChecker;
import ir.mrstudios.awscleaner.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class HealthCheckService {
    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckService.class);
    private final LightsailClient aws;
    private final SshChecker ssh;
    private final IpRotationService rotation;
    private final DnsSyncService dns;
    private final RotationJournal journal;
    private final boolean dryRun;

    public HealthCheckService(LightsailClient aws, SshChecker ssh, IpRotationService rotation,
                              DnsSyncService dns, RotationJournal journal, boolean dryRun) {
        this.aws = aws;
        this.ssh = ssh;
        this.rotation = rotation;
        this.dns = dns;
        this.journal = journal;
        this.dryRun = dryRun;
    }

    public void check() throws IOException, InterruptedException {
        LOG.info("Fetching Lightsail instance information");
        if (!running()) return;
        Instance instance = aws.getInstance();
        if (!dryRun) journal.bind(instance.arn());

        if (journal.state().pending() != null || journal.state().trade() != null) {
            String recovered;
            if (dryRun) {
                LOG.info("Dry run: a previous rotation is pending; observing the current attachment only");
                recovered = null;
            } else {
                try {
                    recovered = rotation.recover();
                } catch (IOException e) {
                    if (journal.state().trade() != null || journal.state().pending() == null
                            || journal.state().pending().action() != Action.RELEASE) throw e;
                    LOG.warn("Previously detached IP cleanup remains pending: {}", e.getMessage());
                    recovered = null;
                }
            }
            if (recovered != null) {
                syncIfCurrent(recovered);
                return;
            }
            instance = aws.getInstance();
        }

        String ip = instance.publicIpAddress();
        if (!Ipv4.valid(ip)) {
            LOG.warn("Lightsail instance has no valid public IPv4 address");
            return;
        }
        LOG.info("Current public IP is {}", ip);
        if (ssh.reachable(ip)) {
            syncIfCurrent(ip);
        } else if (journal.state().pending() != null || journal.state().trade() != null) {
            LOG.info("The previously recorded Lightsail change must be reconciled before another rotation");
        } else {
            String rotated = rotation.rotate(ip);
            if (rotated != null) syncIfCurrent(rotated);
        }
    }

    private void syncIfCurrent(String ip) throws IOException, InterruptedException {
        if (!running()) return;
        var fresh = aws.getInstance();
        if (!dryRun) journal.bind(fresh.arn());
        if (!ip.equals(fresh.publicIpAddress())) {
            LOG.info("Instance IP changed after SSH verification; deferring DNS synchronization");
            return;
        }
        boolean complete = dns.sync(ip);
        rotation.clean(complete);
    }

    private boolean running() throws IOException, InterruptedException {
        String state = aws.getInstanceState();
        if ("running".equalsIgnoreCase(state)) return true;
        LOG.info("Lightsail instance is not running; skipping IP and DNS changes");
        return false;
    }
}
