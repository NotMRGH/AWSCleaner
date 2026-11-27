package ir.mrstudios.awscleaner.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;
import ir.mrstudios.awscleaner.model.RotationState;
import ir.mrstudios.awscleaner.model.RotationState.Pending;
import ir.mrstudios.awscleaner.model.RotationState.Stage;
import ir.mrstudios.awscleaner.model.RotationState.Trade;
import ir.mrstudios.awscleaner.util.HttpJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class RotationJournal implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile RotationState state;
    private JsonElement trafficMonitor;

    public RotationJournal(Path file) throws IOException {
        path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Rotation state is already locked by another process");
            load();
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("Rotation state is already locked by another process");
            }
            throw e;
        }
    }

    private void load() throws IOException {
        if (Files.notExists(path)) {
            state = RotationState.empty();
            return;
        }
        var document = HttpJson.object(Files.readString(path, StandardCharsets.UTF_8), "Rotation state");
        try {
            var loaded = GSON.fromJson(document, RotationState.class);
            if (loaded.version() != RotationState.VERSION
                    || (loaded.instanceArn() == null && !loaded.equals(RotationState.empty()))
                    || (loaded.instanceArn() != null && loaded.instanceArn().isBlank())) {
                throw new IllegalArgumentException();
            }
            state = loaded;
            trafficMonitor = document.get("trafficMonitor");
        } catch (RuntimeException e) {
            throw new IOException("Invalid or unsupported rotation state file");
        }
    }

    public RotationState state() { return state; }

    synchronized JsonElement trafficState() {
        return trafficMonitor == null ? null : trafficMonitor.deepCopy();
    }

    synchronized void saveTraffic(JsonObject next) throws IOException {
        write(state, next);
        trafficMonitor = next;
    }

    public void bind(String instanceArn) throws IOException {
        if (state.instanceArn() == null) {
            commit(new RotationState(RotationState.VERSION, instanceArn, List.of(), 0, state.owned(), state.discarded(),
                    null, null));
        } else if (!state.instanceArn().equals(instanceArn)) {
            throw new IOException("Rotation state belongs to a different Lightsail instance ARN");
        }
    }

    public List<Long> recentAttempts(long now) {
        long cutoff = now - Duration.ofHours(1).toMillis();
        return state.attempts().stream().filter(time -> time > cutoff).toList();
    }

    public void begin(Trade trade, long now, long cooldownUntil) throws IOException {
        if (state.trade() != null || state.pending() != null) throw new IOException("An AWS change is already pending");
        var attempts = new ArrayList<>(recentAttempts(now));
        attempts.add(now);
        commit(new RotationState(RotationState.VERSION, state.instanceArn(), attempts, cooldownUntil,
                state.owned(), state.discarded(), trade, null));
    }

    public void extendCooldown(long until) throws IOException {
        if (until <= state.cooldownUntil()) return;
        commit(new RotationState(RotationState.VERSION, state.instanceArn(), state.attempts(), until,
                state.owned(), state.discarded(), state.trade(), state.pending()));
    }

    public void stage(Stage stage) throws IOException {
        copy(state.trade().stage(stage), state.pending(), null, false, null);
    }

    public void pending(Pending pending) throws IOException {
        copy(state.trade(), pending, null, false, null);
    }

    public void resolved(StaticIp allocated) throws IOException {
        String released = state.pending().action() == Action.RELEASE ? state.pending().name() : null;
        copy(state.trade(), null, allocated, false, released);
    }

    public void finish(boolean discardCandidate) throws IOException {
        if (state.pending() != null) throw new IOException("Cannot finish while an AWS operation is pending");
        copy(null, null, null, discardCandidate, null);
    }

    public void forget(String name) throws IOException {
        if (state.owned().containsKey(name)) copy(state.trade(), state.pending(), null, false, name);
    }

    private void copy(Trade trade, Pending pending, StaticIp add, boolean discardCandidate, String remove)
            throws IOException {
        var owned = new HashMap<>(state.owned());
        var discarded = new HashSet<>(state.discarded());
        if (add != null) owned.put(add.name(), add.arn());
        if (discardCandidate && owned.containsKey(state.trade().candidate())) discarded.add(state.trade().candidate());
        if (remove != null) {
            owned.remove(remove);
            discarded.remove(remove);
        }
        commit(new RotationState(RotationState.VERSION, state.instanceArn(), state.attempts(), state.cooldownUntil(),
                owned, discarded, trade, pending));
    }

    private synchronized void commit(RotationState next) throws IOException {
        write(next, trafficMonitor);
        state = next;
    }

    private void write(RotationState rotation, JsonElement monitor) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".rotation-", ".tmp");
        try {
            JsonObject document = GSON.toJsonTree(rotation).getAsJsonObject();
            if (monitor != null) document.add("trafficMonitor", monitor);
            byte[] bytes = GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }
}
