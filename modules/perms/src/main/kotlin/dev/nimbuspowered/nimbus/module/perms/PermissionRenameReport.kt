package dev.nimbuspowered.nimbus.module.perms

data class PermissionRenameReport(
    val oldNode: String,
    val newNode: String,
    val groupsUpdated: List<String>,
    val totalReplacements: Int
) {
    val noop: Boolean get() = groupsUpdated.isEmpty()
}
