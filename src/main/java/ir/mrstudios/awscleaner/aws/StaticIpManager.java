package ir.mrstudios.awscleaner.aws;

import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.Instance;
import ir.mrstudios.awscleaner.model.Lightsail.Operation;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;
import ir.mrstudios.awscleaner.model.RotationState.Pending;
import ir.mrstudios.awscleaner.service.RotationJournal;
import ir.mrstudios.awscleaner.util.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class StaticIpManager {
    private static final Logger LOG = LoggerFactory.getLogger(StaticIpManager.class);
    private final LightsailClient client;
    private final RotationJournal journal;
    private final String instanceName;
    private final int operationTimeoutSeconds;
    private final Clock clock;

    public StaticIpManager(LightsailClient client, RotationJournal journal, String instanceName,
                           int operationTimeoutSeconds, Clock clock) {
        this.client = client;
        this.journal = journal;
        this.instanceName = instanceName;
        this.operationTimeoutSeconds = operationTimeoutSeconds;
        this.clock = clock;
    }

    public record Inventory(Instance instance, StaticIp attached, int count) {}

    public Inventory inventory() throws IOException, InterruptedException {
        return inventory(0);
    }

    private Inventory inventory(long deadline) throws IOException, InterruptedException {
        Instance instance = client.getInstance(deadline);
        if (journal.state().instanceArn() != null && !journal.state().instanceArn().equals(instance.arn())) {
            throw new IOException("Lightsail instance ARN changed");
        }
        List<StaticIp> ips = client.getStaticIps(deadline);
        var matching = ips.stream().filter(ip -> ip.attachedTo(instanceName)).toList();
        if (matching.size() > 1 || instance.isStaticIp() != !matching.isEmpty()) {
            throw new InventoryLagException();
        }
        StaticIp attached = matching.isEmpty() ? null : matching.get(0);
        if (attached != null && !attached.ipAddress().equals(instance.publicIpAddress())) {
            throw new InventoryLagException();
        }
        return new Inventory(instance, attached, ips.size());
    }

    public void awaitAssignment(StaticIp expected) throws IOException, InterruptedException {
        long deadline = deadline();
        long pauseMillis = 750;
        do {
            try {
                var current = inventory(deadline).attached();
                if (expected == null && current == null) return;
                if (expected != null && current != null && Objects.equals(expected.arn(), current.arn())) return;
            } catch (InventoryLagException ignored) {
            }
            pauseMillis = pause(deadline, pauseMillis);
        } while (System.nanoTime() < deadline);
        throw new IOException("Lightsail did not confirm the expected instance IP assignment");
    }

    public Optional<StaticIp> find(String name) throws IOException, InterruptedException {
        return client.getStaticIp(name);
    }

    public boolean running() throws IOException, InterruptedException {
        return "running".equalsIgnoreCase(client.getInstanceState());
    }

    public StaticIp allocate(String name) throws IOException, InterruptedException {
        if (find(name).isPresent()) throw new IOException("Generated static IP name already exists");
        return submit(Action.ALLOCATE, name, null).orElseThrow(() -> new IOException("No allocated static IP"));
    }

    public void detach(StaticIp expected) throws IOException, InterruptedException {
        StaticIp fresh = requireSame(expected);
        if (!fresh.attachedTo(instanceName)) throw new IOException("Static IP attachment changed before detach");
        if (!client.getInstance().arn().equals(journal.state().instanceArn())) {
            throw new IOException("Lightsail instance identity changed before detach");
        }
        if (!running()) throw new IOException("Lightsail instance is no longer running");
        submit(Action.DETACH, fresh.name(), fresh.arn());
    }

    public void attach(StaticIp expected) throws IOException, InterruptedException {
        StaticIp fresh = requireSame(expected);
        if (fresh.isAttached()) throw new IOException("Static IP is already attached");
        Inventory inventory = inventory();
        if (inventory.attached() != null) throw new IOException("Instance already has a static IP");
        if (!running()) throw new IOException("Lightsail instance is no longer running");
        submit(Action.ATTACH, fresh.name(), fresh.arn());
    }

    public void releaseOwned(String name, String arn) throws IOException, InterruptedException {
        if (!arn.equals(journal.state().owned().get(name))) return;
        var observed = find(name);
        if (observed.isEmpty() || !arn.equals(observed.get().arn())) {
            journal.forget(name);
            return;
        }
        StaticIp ip = observed.get();
        if (ip.isAttached()) {
            if (!ip.attachedTo(instanceName)) {
                LOG.warn("Removing {} from local ownership tracking after it was attached to another instance", name);
                journal.forget(name);
            }
            return;
        }
        submit(Action.RELEASE, name, arn);
        LOG.info("Released detached program-owned static IP {}", name);
    }

    private StaticIp requireSame(StaticIp expected) throws IOException, InterruptedException {
        return find(expected.name()).filter(resource -> expected.arn().equals(resource.arn()))
                .orElseThrow(() -> new IOException("Static IP identity changed before mutation"));
    }

    private Optional<StaticIp> submit(Action action, String name, String arn) throws IOException, InterruptedException {
        if (journal.state().pending() != null) throw new IOException("Previous Lightsail mutation is unresolved");
        journal.pending(new Pending(action, name, arn, clock.millis(), List.of()));
        List<Operation> operations;
        try {
            operations = client.mutate(action, name);
        } catch (LightsailClient.RequestNotSentException e) {
            journal.pending(null);
            throw e;
        } catch (ApiException e) {
            if (e.rejectedMutation()) {
                if (e.code().equals("OperationFailureException")) {
                    var observed = find(name);
                    if (effectPresent(journal.state().pending(), observed)) {
                        journal.resolved(action == Action.ALLOCATE ? observed.orElseThrow() : null);
                        return observed;
                    }
                }
                journal.pending(null);
                throw e;
            }
            LOG.warn("Outcome of {} on {} is uncertain: {}", action.target(), name, e.getMessage());
            return awaitPending();
        } catch (IOException e) {
            LOG.warn("Outcome of {} on {} is uncertain: {}", action.target(), name, e.getMessage());
            return awaitPending();
        }
        storeOperationIds(operations);
        return awaitPending();
    }

    public Optional<StaticIp> awaitPending() throws IOException, InterruptedException {
        if (journal.state().pending() == null) return Optional.empty();
        long deadline = deadline();
        long pauseMillis = 750;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Pending pending = journal.state().pending();
            Optional<StaticIp> observed;
            try {
                observed = client.getStaticIp(pending.name(), deadline);
                List<Operation> operations = operations(pending, deadline);
                boolean terminal = !operations.isEmpty() && operations.stream().allMatch(Operation::terminal);
                if ((terminal || operations.isEmpty()) && effectPresent(pending, observed)) {
                    journal.resolved(pending.action() == Action.ALLOCATE ? observed.orElseThrow() : null);
                    return observed;
                }
                if (terminal && operations.stream().anyMatch(operation -> !operation.succeeded())) {
                    journal.pending(null);
                    throw new OperationFailedException(pending.action());
                }
            } catch (ApiException e) {
                if (!e.retryable() && !e.missing()) throw e;
            }
            pauseMillis = pause(deadline, pauseMillis);
        } while (System.nanoTime() < deadline);

        Pending pending = journal.state().pending();
        throw new IOException("Lightsail " + pending.action().target() + " remains unconfirmed for " + pending.name()
                + "; the operation is recorded for later reconciliation");
    }

    private List<Operation> operations(Pending pending, long deadline) throws IOException, InterruptedException {
        if (!pending.operationIds().isEmpty()) {
            List<Operation> current = new ArrayList<>();
            for (String id : pending.operationIds()) current.add(client.getOperation(id, deadline));
            return current;
        }
        List<Operation> matching = client.getOperationsForResource(pending.name(), deadline).stream()
                .filter(op -> pending.name().equals(op.resourceName()) && pending.action().target().equals(op.operationType())
                        && op.createdAt() * 1000 >= pending.requestedAt())
                .toList();
        if (matching.size() == 1) {
            storeOperationIds(matching);
            return matching;
        }
        if (matching.size() > 1) throw new IOException("Multiple Lightsail operations match the pending mutation");
        return List.of();
    }

    private void storeOperationIds(List<Operation> operations) throws IOException {
        var ids = new LinkedHashSet<String>();
        for (Operation operation : operations) {
            if (operation.id() != null && !operation.id().isBlank()) ids.add(operation.id());
        }
        if (!ids.isEmpty()) journal.pending(journal.state().pending().withOperations(List.copyOf(ids)));
    }

    private boolean effectPresent(Pending pending, Optional<StaticIp> observed) {
        var same = observed.filter(ip -> ip.arn().equals(pending.arn()));
        return switch (pending.action()) {
            case ALLOCATE -> observed.isPresent();
            case ATTACH -> same.filter(ip -> ip.attachedTo(instanceName)).isPresent();
            case DETACH -> same.filter(ip -> !ip.isAttached()).isPresent();
            case RELEASE -> same.isEmpty();
        };
    }

    private long deadline() {
        return System.nanoTime() + Duration.ofSeconds(operationTimeoutSeconds).toNanos();
    }

    private static long pause(long deadline, long pauseMillis) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(pauseMillis)));
        return Math.min(5000, pauseMillis * 2);
    }

    private static final class InventoryLagException extends IOException {
        InventoryLagException() { super("Lightsail instance and static IP inventory disagree"); }
    }

    public static final class OperationFailedException extends IOException {
        OperationFailedException(Action action) {
            super("Lightsail " + action.target() + " ended unsuccessfully");
        }
    }
}
