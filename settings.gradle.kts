rootProject.name = "nimbus"

// Core
include("nimbus-core")
include("nimbus-protocol")
include("nimbus-agent")
include("nimbus-cli")

// Core plugins — deployed on every service/proxy regardless of which
// modules are installed. Live under plugins/ for that reason.
include("nimbus-sdk")
project(":nimbus-sdk").projectDir = file("plugins/sdk")

include("nimbus-bridge")
project(":nimbus-bridge").projectDir = file("plugins/bridge")

// Module-owned plugins — each sits next to the module that ships it so
// module + its Minecraft-side code form one logical unit on disk. Gradle
// project names keep the `nimbus-<name>` prefix for backwards compat with
// embedded shadowJar references.
include("nimbus-display")
project(":nimbus-display").projectDir = file("modules/display/plugin")

include("nimbus-perms")
project(":nimbus-perms").projectDir = file("modules/perms/plugin")

include("nimbus-punishments-velocity")
project(":nimbus-punishments-velocity").projectDir = file("modules/punishments/plugin-velocity")

include("nimbus-punishments-backend")
project(":nimbus-punishments-backend").projectDir = file("modules/punishments/plugin-backend")

include("nimbus-resourcepacks")
project(":nimbus-resourcepacks").projectDir = file("modules/resourcepacks/plugin")

include("nimbus-auth-velocity")
project(":nimbus-auth-velocity").projectDir = file("modules/auth/plugin-velocity")

// Controller Modules
include("nimbus-module-api")
project(":nimbus-module-api").projectDir = file("modules/api")

include("nimbus-module-perms")
project(":nimbus-module-perms").projectDir = file("modules/perms")

include("nimbus-module-display")
project(":nimbus-module-display").projectDir = file("modules/display")

include("nimbus-module-scaling")
project(":nimbus-module-scaling").projectDir = file("modules/scaling")

include("nimbus-module-players")
project(":nimbus-module-players").projectDir = file("modules/players")

include("nimbus-module-punishments")
project(":nimbus-module-punishments").projectDir = file("modules/punishments")

include("nimbus-module-resourcepacks")
project(":nimbus-module-resourcepacks").projectDir = file("modules/resourcepacks")

include("nimbus-module-backup")
project(":nimbus-module-backup").projectDir = file("modules/backup")

include("nimbus-module-docker")
project(":nimbus-module-docker").projectDir = file("modules/docker")

include("nimbus-module-auth")
project(":nimbus-module-auth").projectDir = file("modules/auth")
