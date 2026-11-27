package ir.mrstudios.awscleaner.model;

public final class Lightsail {
    private Lightsail() {}

    public record Instance(String arn, String publicIpAddress, boolean isStaticIp) {}
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
