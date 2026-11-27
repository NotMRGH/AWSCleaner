package ir.mrstudios.awscleaner.model;

import ir.mrstudios.awscleaner.model.Lightsail.Action;
import ir.mrstudios.awscleaner.model.Lightsail.StaticIp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RotationState(int version, String instanceArn, List<Long> attempts, long cooldownUntil,
                            Map<String, String> owned, Set<String> discarded, Trade trade, Pending pending) {
    public static final int VERSION = 1;

    public RotationState {
        attempts = List.copyOf(attempts);
        owned = Map.copyOf(owned);
        discarded = Set.copyOf(discarded);
        if (attempts.stream().anyMatch(time -> time < 0) || cooldownUntil < 0 || !owned.keySet().containsAll(discarded)) {
            throw new IllegalArgumentException("Invalid rotation state");
        }
    }

    public static RotationState empty() {
        return new RotationState(VERSION, null, List.of(), 0, Map.of(), Set.of(), null, null);
    }

    public record Trade(String originalIp, StaticIp originalStatic, String candidate, Stage stage) {
        public Trade {
            Objects.requireNonNull(originalIp);
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(stage);
        }
        public Trade stage(Stage next) { return new Trade(originalIp, originalStatic, candidate, next); }
    }

    public enum Stage { PREPARING, SWAPPING, ROLLING_BACK }

    public record Pending(Action action, String name, String arn, long requestedAt, List<String> operationIds) {
        public Pending {
            Objects.requireNonNull(action);
            Objects.requireNonNull(name);
            if (action != Action.ALLOCATE) Objects.requireNonNull(arn);
            operationIds = List.copyOf(operationIds);
        }
        public Pending withOperations(List<String> ids) { return new Pending(action, name, arn, requestedAt, ids); }
    }
}
