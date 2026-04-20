package dev.nimbuspowered.nimbus.module.docker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure filters over `/containers/json` entries used by [DockerServiceHandleFactory.recover].
 *
 * Kept separate so the rules stay testable without spinning up the factory
 * (which needs a DockerClient + ConfigManager).
 */
internal object DockerContainerMatcher {

    /** True when the container was created by Nimbus and is currently running. */
    fun isManagedRunning(container: JsonObject): Boolean =
        isManaged(container) && state(container) == "running"

    /** True when the container was created by Nimbus but has exited (crash recovery candidate). */
    fun isManagedExited(container: JsonObject): Boolean =
        isManaged(container) && state(container) == "exited"

    fun isManaged(container: JsonObject): Boolean =
        container["Labels"]?.jsonObject
            ?.get("nimbus.managed")?.jsonPrimitive?.content == "true"

    fun state(container: JsonObject): String =
        container["State"]?.jsonPrimitive?.content ?: ""

    fun serviceName(container: JsonObject): String? =
        container["Labels"]?.jsonObject
            ?.get("nimbus.service")?.jsonPrimitive?.content
}
