package ir.mrstudios.awscleaner.ssh;

import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.network.DirectSocketFactory;
import ir.mrstudios.awscleaner.util.Ipv4;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class SshChecker {
    private static final Logger LOG = LoggerFactory.getLogger(SshChecker.class);
    private final AppConfig.Ssh config;

    public SshChecker(AppConfig.Ssh config) {
        this.config = config;
    }

    public boolean reachable(String ipv4) throws InterruptedException {
        if (!Ipv4.valid(ipv4)) throw new IllegalArgumentException("SSH requires an IPv4 address");
        for (int attempt = 1; attempt <= config.attempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (handshake(ipv4)) return true;
            LOG.warn("SSH handshake failed for {} ({}/{})", ipv4, attempt, config.attempts());
            if (attempt < config.attempts()) TimeUnit.SECONDS.sleep(config.retryDelaySeconds());
        }
        return false;
    }

    private boolean handshake(String ip) {
        int timeout = Math.toIntExact(TimeUnit.SECONDS.toMillis(config.timeoutSeconds()));
        boolean connected = false;
        try (var ssh = new SSHClient()) {
            ssh.setSocketFactory(new DirectSocketFactory());
            ssh.setConnectTimeout(timeout);
            ssh.setTimeout(timeout);
            if (config.fingerprints().isEmpty()) {
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                for (String fingerprint : config.fingerprints()) ssh.addHostKeyVerifier(fingerprint);
            }
            ssh.connect(ip, config.port());
            connected = true;
            LOG.info("SSH handshake successful for {}", ip);
        } catch (IOException e) {
            LOG.debug("SSH probe for {} ended with {}", ip, e.getClass().getSimpleName());
        }
        return connected;
    }
}
