package ir.mrstudios.awscleaner.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;
import ir.mrstudios.awscleaner.model.RotationState;
import ir.mrstudios.awscleaner.model.RotationState.Pending;
import ir.mrstudios.awscleaner.model.RotationState.Stage;
import ir.mrstudios.awscleaner.model.RotationState.Trade;

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
    private RotationState state;

    public RotationJournal(Path file) throws IOException {
        path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Rotation state is already locked by another process");
            state = load(path);
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("Rotation state is already locked by another process");
            }
            throw e;
        }
    }

    private static RotationState load(Path path) throws IOException {
        if (Files.notExists(path)) return RotationState.empty();
        try {
            var loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), RotationState.class);
            if (loaded == null || loaded.version() != RotationState.VERSION || loaded.instanceArn() == null
                    || loaded.instanceArn().isBlank()) throw new IllegalArgumentException();
            return loaded;
        } catch (RuntimeException e) {
            throw new IOException("Invalid or unsupported rotation state file");
        }
    }

    public RotationState state() { return state; }

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

    private void commit(RotationState next) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".rotation-", ".tmp");
        try {
            byte[] bytes = GSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
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
