package ir.mrstudios.awscleaner.scheduler;

import ir.mrstudios.awscleaner.config.AppConfig;
import ir.mrstudios.awscleaner.service.TrafficMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TrafficMonitorScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficMonitorScheduler.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "lightsail-traffic-monitor"));
    private final AppConfig.TrafficMonitor config;
    private final TrafficMonitorService monitor;
    private boolean started;
    private boolean initialRun = true;

    public TrafficMonitorScheduler(AppConfig.TrafficMonitor config, TrafficMonitorService monitor) {
        this.config = config;
        this.monitor = monitor;
    }

    public synchronized void start() {
        if (started || !config.enabled()) return;
        long interval = config.checkIntervalMinutes();
        executor.scheduleWithFixedDelay(this::runCycle, 0, interval, TimeUnit.MINUTES);
        started = true;
        LOG.info("Lightsail traffic check interval is {} minute(s), with fixed delay", interval);
    }

    private void runCycle() {
        try {
            if (initialRun) {
                initialRun = false;
                monitor.checkOnStartup();
            } else {
                monitor.check();
            }
        } catch (IOException e) {
            LOG.warn("Lightsail traffic check could not complete: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Lightsail traffic check interrupted");
        } catch (RuntimeException e) {
            LOG.error("Unexpected Lightsail traffic check failure ({})", e.getClass().getSimpleName());
        }
    }

    public void stop() {
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                LOG.warn("Lightsail traffic check is still exiting");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
