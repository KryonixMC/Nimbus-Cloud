package dev.nimbuspowered.nimbus.sdk;

/**
 * Represents a Nimbus server group.
 */
public class NimbusGroup {

    private final String name;
    private final String type;
    private final String software;
    private final String version;
    private final String template;
    private final int activeInstances;
    private final int maxInstances;
    private final int maxPlayers;

    public NimbusGroup(String name, String type, String software, String version, String template,
                       int activeInstances, int maxInstances, int maxPlayers) {
        this.name = name;
        this.type = type;
        this.software = software;
        this.version = version;
        this.template = template;
        this.activeInstances = activeInstances;
        this.maxInstances = maxInstances;
        this.maxPlayers = maxPlayers;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getSoftware() { return software; }
    public String getVersion() { return version; }
    public String getTemplate() { return template; }
    public int getActiveInstances() { return activeInstances; }
    public int getMaxInstances() { return maxInstances; }
    public int getMaxPlayers() { return maxPlayers; }

    public boolean isDynamic() { return "DYNAMIC".equals(type); }
    public boolean isStatic() { return "STATIC".equals(type); }

    @Override
    public String toString() {
        return "NimbusGroup{name='" + name + "', type=" + type + ", software=" + software +
                " " + version + ", instances=" + activeInstances + "/" + maxInstances + "}";
    }
}
