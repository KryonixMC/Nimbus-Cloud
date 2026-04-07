package dev.nimbuspowered.nimbus.perms.handler;

import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.ServerOperator;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Custom Permissible that supports wildcard permission matching.
 * Can be injected into players via reflection to override Bukkit's default
 * exact-match permission checks.
 */
public class PermissibleInjector extends PermissibleBase {

    private final PermissibleBase original;
    private final Set<String> nimbusPermissions = new CopyOnWriteArraySet<>();

    public PermissibleInjector(ServerOperator operator, PermissibleBase original) {
        super(operator);
        this.original = original;
    }

    public void setPermissions(Set<String> permissions) {
        nimbusPermissions.clear();
        nimbusPermissions.addAll(permissions);
    }

    public Set<String> getPermissions() {
        return Collections.unmodifiableSet(nimbusPermissions);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (matchesNimbus(permission)) return true;
        return original.hasPermission(permission);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        if (matchesNimbus(permission.getName())) return true;
        return original.hasPermission(permission);
    }

    @Override
    public boolean isPermissionSet(String permission) {
        if (matchesNimbus(permission)) return true;
        return original.isPermissionSet(permission);
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        if (matchesNimbus(permission.getName())) return true;
        return original.isPermissionSet(permission);
    }

    @Override
    public void recalculatePermissions() {
        original.recalculatePermissions();
    }

    @Override
    public PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
        return original.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
        return original.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
        return original.addAttachment(plugin, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) {
        return original.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        original.removeAttachment(attachment);
    }

    @Override
    public boolean isOp() {
        return original.isOp();
    }

    @Override
    public void setOp(boolean value) {
        original.setOp(value);
    }

    @Override
    public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
        return original.getEffectivePermissions();
    }

    private boolean matchesNimbus(String permission) {
        if (nimbusPermissions.isEmpty()) return false;
        String lower = permission.toLowerCase();

        if (nimbusPermissions.contains(lower)) return true;
        if (nimbusPermissions.contains("*")) return true;

        String[] parts = lower.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) prefix.append(".");
            prefix.append(parts[i]);
            if (nimbusPermissions.contains(prefix + ".*")) return true;
        }

        return false;
    }

    public PermissibleBase getOriginal() {
        return original;
    }
}
