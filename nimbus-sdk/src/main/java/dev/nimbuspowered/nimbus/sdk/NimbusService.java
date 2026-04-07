package dev.nimbuspowered.nimbus.sdk;

/**
 * Represents a Nimbus-managed service instance.
 */
public class NimbusService {

    private final String name;
    private final String groupName;
    private final int port;
    private final String state;
    private final String customState;
    private final Long pid;
    private final int playerCount;
    private final String startedAt;
    private final int restartCount;
    private final String uptime;
    private final double tps;
    private final long memoryUsedMb;
    private final long memoryMaxMb;
    private final boolean healthy;

    public NimbusService(String name, String groupName, int port, String state, String customState,
                         Long pid, int playerCount, String startedAt, int restartCount, String uptime) {
        this(name, groupName, port, state, customState, pid, playerCount, startedAt, restartCount, uptime, 20.0, 0, 0, true);
    }

    public NimbusService(String name, String groupName, int port, String state, String customState,
                         Long pid, int playerCount, String startedAt, int restartCount, String uptime,
                         double tps, long memoryUsedMb, long memoryMaxMb, boolean healthy) {
        this.name = name;
        this.groupName = groupName;
        this.port = port;
        this.state = state;
        this.customState = customState;
        this.pid = pid;
        this.playerCount = playerCount;
        this.startedAt = startedAt;
        this.restartCount = restartCount;
        this.uptime = uptime;
        this.tps = tps;
        this.memoryUsedMb = memoryUsedMb;
        this.memoryMaxMb = memoryMaxMb;
        this.healthy = healthy;
    }

    public String getName() { return name; }
    public String getGroupName() { return groupName; }
    public int getPort() { return port; }
    public String getState() { return state; }
    public String getCustomState() { return customState; }
    public Long getPid() { return pid; }
    public int getPlayerCount() { return playerCount; }
    public String getStartedAt() { return startedAt; }
    public int getRestartCount() { return restartCount; }
    public String getUptime() { return uptime; }
    public double getTps() { return tps; }
    public long getMemoryUsedMb() { return memoryUsedMb; }
    public long getMemoryMaxMb() { return memoryMaxMb; }
    public boolean isHealthy() { return healthy; }

    /** Returns true if this service is READY, has no custom state, and is healthy. */
    public boolean isRoutable() {
        return "READY".equals(state) && customState == null && healthy;
    }

    /** Returns true if this service is READY regardless of custom state or health. */
    public boolean isReady() {
        return "READY".equals(state);
    }

    @Override
    public String toString() {
        return "NimbusService{name='" + name + "', group='" + groupName + "', state=" + state +
                (customState != null ? ", customState=" + customState : "") +
                ", players=" + playerCount + ", tps=" + String.format("%.1f", tps) +
                ", healthy=" + healthy + ", port=" + port + "}";
    }
}
