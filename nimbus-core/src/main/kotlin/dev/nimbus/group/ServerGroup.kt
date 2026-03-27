package dev.nimbus.group

import dev.nimbus.config.GroupConfig
import dev.nimbus.config.GroupType

class ServerGroup(val config: GroupConfig) {

    val name: String get() = config.group.name

    val isStatic: Boolean get() = config.group.type == GroupType.STATIC

    val isDynamic: Boolean get() = config.group.type == GroupType.DYNAMIC

    val minInstances: Int get() = config.group.scaling.minInstances

    val maxInstances: Int get() = config.group.scaling.maxInstances
}
