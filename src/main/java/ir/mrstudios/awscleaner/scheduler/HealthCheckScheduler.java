package ir.mrstudios.awscleaner.scheduler;

import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.service.HealthCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HealthCheckScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckScheduler.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "lightsail-health-check"));
    private final AppConfig.Application config;
    private final HealthCheckService checks;

    public HealthCheckScheduler(AppConfig.Application config, HealthCheckService checks) {
        this.config = config;
        this.checks = checks;
    }

    public void start() {
        long interval = config.checkIntervalMinutes();
        LOG.info("Health check interval is {} minute(s), with fixed delay", interval);
        executor.scheduleWithFixedDelay(this::runCycle, config.checkOnStartup() ? 0 : interval,
                interval, TimeUnit.MINUTES);
    }

    private void runCycle() {
        try {
            checks.check();
        } catch (IOException e) {
            LOG.warn("Health check could not complete: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Health check interrupted");
        } catch (RuntimeException e) {
            LOG.error("Unexpected health check failure ({})", e.getClass().getSimpleName());
        } finally {
            if (!executor.isShutdown()) LOG.info("Next health check in {} minute(s)", config.checkIntervalMinutes());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                LOG.warn("Health check is still exiting; the journal will be reconciled on restart");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
