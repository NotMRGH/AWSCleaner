package ir.mrstudios.awscleaner.model;

public final class Lightsail {
    private Lightsail() {}

    public record Instance(String arn, String publicIpAddress, boolean isStaticIp) {}
    public record TrafficInstance(String arn, String name, String bundleId, Double createdAt, Networking networking) {
        public Integer allocatedTransfer() {
            return networking == null || networking.monthlyTransfer() == null
                    ? null : networking.monthlyTransfer().gbPerMonthAllocated();
        }
    }
    public record Networking(MonthlyTransfer monthlyTransfer) {}
    public record MonthlyTransfer(Integer gbPerMonthAllocated) {}
    public record Bundle(String bundleId, Integer transferPerMonthInGb) {}
    public record Region(String name, String displayName) {}
    public record MetricPoint(Double timestamp, Double sum, String unit) {}
    public enum NetworkMetric { NetworkIn, NetworkOut }
    public record StaticIp(String name, String arn, String ipAddress, boolean isAttached, String attachedTo) {
        public boolean attachedTo(String instanceName) {
            return isAttached && instanceName.equals(attachedTo);
        }
    }
    public record Operation(String id, String resourceName, String operationType, String status,
                            boolean isTerminal, double createdAt) {
        public boolean failed() { return "Failed".equals(status); }
        public boolean terminal() { return isTerminal || failed() || succeeded(); }
        public boolean succeeded() { return "Succeeded".equals(status) || "Completed".equals(status); }
    }
    public enum Action {
        ALLOCATE("AllocateStaticIp"), ATTACH("AttachStaticIp"), DETACH("DetachStaticIp"), RELEASE("ReleaseStaticIp");

        private final String target;
        Action(String target) { this.target = target; }
        public String target() { return target; }
    }
}
