@file:Suppress("DEPRECATION", "PackageDirectoryMismatch", "unused")
@file:JvmName("DeprecatedModuleApi")

package dev.nimbuspowered.nimbus.module

import dev.nimbuspowered.nimbus.module.api.hasPermission as apiHasPermission
import dev.nimbuspowered.nimbus.module.api.service as apiService

// -----------------------------------------------------------------------------
// Deprecated re-exports for the old `dev.nimbuspowered.nimbus.module` package.
//
// The public Module API moved to `dev.nimbuspowered.nimbus.module.api` in 0.13.0.
// These typealiases keep third-party module source code compiling for one release
// cycle. Scheduled for removal in Nimbus 1.0.
//
// NOTE: typealiases cannot re-export extension functions; the two extensions
// (`service<T>()` and `AuthPrincipal.hasPermission`) are re-exported below as
// deprecated wrapper functions that delegate to the new ones. Kotlin does not
// allow package aliases in `import ... as`, so the new package is referenced
// via fully qualified names throughout.
// -----------------------------------------------------------------------------

private const val MSG = "Moved to dev.nimbuspowered.nimbus.module.api in Nimbus 0.13.0"

// --- AuthPrincipal.kt ---

@Deprecated(MSG, ReplaceWith("AuthPrincipal", "dev.nimbuspowered.nimbus.module.api.AuthPrincipal"), DeprecationLevel.WARNING)
typealias AuthPrincipal = dev.nimbuspowered.nimbus.module.api.AuthPrincipal

@Deprecated(MSG, ReplaceWith("PermissionSet", "dev.nimbuspowered.nimbus.module.api.PermissionSet"), DeprecationLevel.WARNING)
typealias PermissionSet = dev.nimbuspowered.nimbus.module.api.PermissionSet

@Deprecated(MSG, ReplaceWith("hasPermission(node)", "dev.nimbuspowered.nimbus.module.api.hasPermission"), DeprecationLevel.WARNING)
fun dev.nimbuspowered.nimbus.module.api.AuthPrincipal.hasPermission(node: String): Boolean =
    this.apiHasPermission(node)

// --- CommandCaller.kt ---

@Deprecated(MSG, ReplaceWith("CommandCaller", "dev.nimbuspowered.nimbus.module.api.CommandCaller"), DeprecationLevel.WARNING)
typealias CommandCaller = dev.nimbuspowered.nimbus.module.api.CommandCaller

// --- CommandOutput.kt ---

@Deprecated(MSG, ReplaceWith("CommandOutput", "dev.nimbuspowered.nimbus.module.api.CommandOutput"), DeprecationLevel.WARNING)
typealias CommandOutput = dev.nimbuspowered.nimbus.module.api.CommandOutput

@Deprecated(MSG, ReplaceWith("SubcommandMeta", "dev.nimbuspowered.nimbus.module.api.SubcommandMeta"), DeprecationLevel.WARNING)
typealias SubcommandMeta = dev.nimbuspowered.nimbus.module.api.SubcommandMeta

@Deprecated(MSG, ReplaceWith("CompletionMeta", "dev.nimbuspowered.nimbus.module.api.CompletionMeta"), DeprecationLevel.WARNING)
typealias CompletionMeta = dev.nimbuspowered.nimbus.module.api.CompletionMeta

@Deprecated(MSG, ReplaceWith("CompletionType", "dev.nimbuspowered.nimbus.module.api.CompletionType"), DeprecationLevel.WARNING)
typealias CompletionType = dev.nimbuspowered.nimbus.module.api.CompletionType

// --- DoctorCheck.kt ---

@Deprecated(MSG, ReplaceWith("DoctorLevel", "dev.nimbuspowered.nimbus.module.api.DoctorLevel"), DeprecationLevel.WARNING)
typealias DoctorLevel = dev.nimbuspowered.nimbus.module.api.DoctorLevel

@Deprecated(MSG, ReplaceWith("DoctorFinding", "dev.nimbuspowered.nimbus.module.api.DoctorFinding"), DeprecationLevel.WARNING)
typealias DoctorFinding = dev.nimbuspowered.nimbus.module.api.DoctorFinding

@Deprecated(MSG, ReplaceWith("DoctorCheck", "dev.nimbuspowered.nimbus.module.api.DoctorCheck"), DeprecationLevel.WARNING)
typealias DoctorCheck = dev.nimbuspowered.nimbus.module.api.DoctorCheck

// --- Migration.kt ---

@Deprecated(MSG, ReplaceWith("Migration", "dev.nimbuspowered.nimbus.module.api.Migration"), DeprecationLevel.WARNING)
typealias Migration = dev.nimbuspowered.nimbus.module.api.Migration

// --- ModuleCommand.kt ---

@Deprecated(MSG, ReplaceWith("ModuleCommand", "dev.nimbuspowered.nimbus.module.api.ModuleCommand"), DeprecationLevel.WARNING)
typealias ModuleCommand = dev.nimbuspowered.nimbus.module.api.ModuleCommand

// --- ModuleContext.kt ---

@Deprecated(MSG, ReplaceWith("ModuleContext", "dev.nimbuspowered.nimbus.module.api.ModuleContext"), DeprecationLevel.WARNING)
typealias ModuleContext = dev.nimbuspowered.nimbus.module.api.ModuleContext

@Deprecated(MSG, ReplaceWith("AuthLevel", "dev.nimbuspowered.nimbus.module.api.AuthLevel"), DeprecationLevel.WARNING)
typealias AuthLevel = dev.nimbuspowered.nimbus.module.api.AuthLevel

@Deprecated(MSG, ReplaceWith("service<T>()", "dev.nimbuspowered.nimbus.module.api.service"), DeprecationLevel.WARNING)
inline fun <reified T : Any> dev.nimbuspowered.nimbus.module.api.ModuleContext.service(): T? =
    this.apiService<T>()

// --- NimbusModule.kt ---

@Deprecated(MSG, ReplaceWith("NimbusModule", "dev.nimbuspowered.nimbus.module.api.NimbusModule"), DeprecationLevel.WARNING)
typealias NimbusModule = dev.nimbuspowered.nimbus.module.api.NimbusModule

@Deprecated(MSG, ReplaceWith("DashboardConfig", "dev.nimbuspowered.nimbus.module.api.DashboardConfig"), DeprecationLevel.WARNING)
typealias DashboardConfig = dev.nimbuspowered.nimbus.module.api.DashboardConfig

@Deprecated(MSG, ReplaceWith("DashboardSection", "dev.nimbuspowered.nimbus.module.api.DashboardSection"), DeprecationLevel.WARNING)
typealias DashboardSection = dev.nimbuspowered.nimbus.module.api.DashboardSection

// --- PluginDeployment.kt ---

@Deprecated(MSG, ReplaceWith("PluginDeployment", "dev.nimbuspowered.nimbus.module.api.PluginDeployment"), DeprecationLevel.WARNING)
typealias PluginDeployment = dev.nimbuspowered.nimbus.module.api.PluginDeployment

@Deprecated(MSG, ReplaceWith("PluginTarget", "dev.nimbuspowered.nimbus.module.api.PluginTarget"), DeprecationLevel.WARNING)
typealias PluginTarget = dev.nimbuspowered.nimbus.module.api.PluginTarget

// --- SessionValidator.kt ---

@Deprecated(MSG, ReplaceWith("SessionValidator", "dev.nimbuspowered.nimbus.module.api.SessionValidator"), DeprecationLevel.WARNING)
typealias SessionValidator = dev.nimbuspowered.nimbus.module.api.SessionValidator
