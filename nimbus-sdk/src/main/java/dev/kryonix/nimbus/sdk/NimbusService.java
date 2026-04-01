package dev.kryonix.nimbus.sdk;

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

    public NimbusService(String name, String groupName, int port, String state, String customState,
                         Long pid, int playerCount, String startedAt, int restartCount, String uptime) {
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

    /** Returns true if this service is READY and has no custom state (can accept new players). */
    public boolean isRoutable() {
        return "READY".equals(state) && customState == null;
    }

    /** Returns true if this service is READY regardless of custom state. */
    public boolean isReady() {
        return "READY".equals(state);
    }

    @Override
    public String toString() {
        return "NimbusService{name='" + name + "', group='" + groupName + "', state=" + state +
                (customState != null ? ", customState=" + customState : "") +
                ", players=" + playerCount + ", port=" + port + "}";
    }
}
