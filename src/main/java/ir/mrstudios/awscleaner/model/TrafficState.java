package ir.mrstudios.awscleaner.model;

import java.time.YearMonth;
import java.util.Map;
import java.util.Set;

public record TrafficState(String month, Map<String, Pool> pools) {
    public TrafficState {
        YearMonth.parse(month);
        pools = Map.copyOf(pools);
    }

    public record Pool(int peakInstances, Double allowancePerInstance, Map<String, Snapshot> instances,
                       Set<Integer> processed) {
        public Pool {
            instances = Map.copyOf(instances);
            processed = Set.copyOf(processed);
            if (peakInstances < 0 || peakInstances > instances.size()
                    || (allowancePerInstance != null && (!Double.isFinite(allowancePerInstance) || allowancePerInstance <= 0))
                    || processed.stream().anyMatch(level -> level < 1 || level > 100)) {
                throw new IllegalArgumentException("Invalid traffic monitor state");
            }
        }

        public static Pool empty() {
            return new Pool(0, null, Map.of(), Set.of());
        }
    }

    public record Snapshot(Totals totals) {}

    public record Totals(double incoming, double outgoing) {
        public Totals {
            if (!Double.isFinite(incoming + outgoing) || incoming < 0 || outgoing < 0) {
                throw new IllegalArgumentException("Invalid traffic totals");
            }
        }

        public double consumed() {
            return incoming + outgoing;
        }
    }
}
