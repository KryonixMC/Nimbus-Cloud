# Custom Modules

Nimbus supports a dynamic module system for extending the controller with custom functionality. Modules are standalone JAR files loaded at startup from the `modules/` directory. They can register console commands, REST API routes, access the database, and interact with core services.

The module API lives in `nimbus-module-api` and has no dependency on `nimbus-core`, keeping your module lightweight and decoupled.

## Quick Start

### Project Setup

Create a new Gradle project with the following `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // Module API — the only required dependency
    compileOnly(files("libs/nimbus-module-api.jar"))

    // Needed for Ktor Route type and Exposed Database type
    compileOnly("io.ktor:ktor-server-core:3.1.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    compileOnly("org.jetbrains.exposed:exposed-core:0.57.0")
    compileOnly("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    // Include resources (ServiceLoader config, module.properties)
    from(sourceSets.main.get().output)
}
```

If you are developing inside the Nimbus monorepo, replace the file dependency with a project reference:

```kotlin
compileOnly(project(":nimbus-module-api"))
```

To access core types directly (e.g. `EventBus`, `ServiceRegistry`), add a compile-only dependency on `nimbus-core`:

```kotlin
compileOnly(project(":nimbus-core"))
```

### Minimal Module

```kotlin
package dev.example.mymodule

import dev.kryonix.nimbus.module.ModuleContext
import dev.kryonix.nimbus.module.NimbusModule

class MyModule : NimbusModule {
    override val id = "my-module"
    override val name = "My Module"
    override val version = "1.0.0"
    override val description = "A minimal example module"

    override suspend fun init(context: ModuleContext) {
        // Register commands, routes, initialize state
    }

    override suspend fun enable() {
        // Called after all modules have been initialized
    }

    override fun disable() {
        // Clean up resources on shutdown
    }
}
```

### Required Resources

Your JAR must include two resource files:

**`META-INF/services/dev.kryonix.nimbus.module.NimbusModule`** — ServiceLoader descriptor pointing to your implementation class:

```
dev.example.mymodule.MyModule
```

**`module.properties`** — metadata used by the setup wizard and `modules` command:

```properties
id=my-module
name=My Module
description=A minimal example module
default=false
```

The `default` property controls whether the module is enabled by default in the first-run setup wizard. Set to `true` for modules that should be on by default.

## Lifecycle

Modules follow a three-phase lifecycle:

```
loadAll()     →  init(context)  →  enable()  →  disable()
              ↑                  ↑              ↑
              Per module,        After ALL       Reverse order
              in load order      modules init    on shutdown
```

1. **Load** — `ModuleManager.loadAll()` scans `modules/` for JAR files, creates a `URLClassLoader` per JAR, and discovers `NimbusModule` implementations via `ServiceLoader`. Duplicate module IDs are rejected.

2. **Init** — `init(context)` is called on each module in load order. This is where you register commands, routes, create database tables, and set up managers. The `ModuleContext` provides access to core services.

3. **Enable** — `enable()` is called after all modules have been initialized. Use this for cross-module interactions or deferred setup that depends on other modules being ready.

4. **Disable** — `disable()` is called during Nimbus shutdown, in reverse load order. Cancel coroutines, close connections, and release resources here.

## ModuleContext API

The `ModuleContext` is passed to `init()` and provides access to the Nimbus runtime:

| Member | Type | Description |
|---|---|---|
| `scope` | `CoroutineScope` | Coroutine scope tied to the Nimbus lifecycle. Launch jobs here. |
| `baseDir` | `Path` | Root directory of the Nimbus installation. |
| `templatesDir` | `Path` | Templates directory (`templates/`). |
| `database` | `Database` | Exposed database instance for direct SQL access. |
| `moduleConfigDir(id)` | `Path` | Returns `config/modules/<id>/`, created if missing. |
| `registerCommand(cmd)` | — | Register a console command. |
| `unregisterCommand(name)` | — | Unregister a command by name. |
| `registerRoutes(block, auth)` | — | Register API routes with the given auth level. |
| `registerPluginDeployment(d)` | — | Register a plugin JAR to deploy to backend services. |
| `registerEventFormatter(type, fn)` | — | Register a console formatter for module events. |
| `registerCompleter(cmd, fn)` | — | Register tab completion for a command. |
| `getService(type)` | `T?` | Retrieve a core service by class. Returns null if unavailable. |
| `registerService(type, instance)` | — | Register a late-initialized service (used by core for ServiceManager). |

### Convenience Extension

The `service<T>()` reified extension avoids passing `Class` objects:

```kotlin
import dev.kryonix.nimbus.module.service

val eventBus = context.service<EventBus>()!!
val registry = context.service<ServiceRegistry>()!!
```

## Console Commands

Implement `ModuleCommand` to add commands to the Nimbus console:

```kotlin
import dev.kryonix.nimbus.module.ModuleCommand

class HelloCommand : ModuleCommand {
    override val name = "hello"
    override val description = "Say hello"
    override val usage = "hello [name]"

    override suspend fun execute(args: List<String>) {
        val target = args.firstOrNull() ?: "World"
        println("Hello, $target!")
    }
}
```

Register it during `init()`:

```kotlin
override suspend fun init(context: ModuleContext) {
    context.registerCommand(HelloCommand())
}
```

The command is immediately available in the Nimbus console. To remove it later:

```kotlin
context.unregisterCommand("hello")
```

::: tip
If your module has a `compileOnly` dependency on `nimbus-core`, you can implement the core `Command` interface instead of `ModuleCommand`. Both work with `registerCommand()`.
:::

## API Routes

Register HTTP endpoints using the Ktor routing DSL. Routes are mounted on the Nimbus API server.

### Route Registration

```kotlin
import dev.kryonix.nimbus.module.AuthLevel

override suspend fun init(context: ModuleContext) {
    context.registerRoutes({
        myModuleRoutes()
    }, AuthLevel.SERVICE)
}
```

### Route Definition

Define routes as an extension function on `Route`:

```kotlin
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.myModuleRoutes() {
    route("/api/my-module") {

        get {
            call.respond(mapOf("status" to "ok"))
        }

        post("/action") {
            val body = call.receive<ActionRequest>()
            // handle request
            call.respond(mapOf("result" to "done"))
        }
    }
}
```

### Auth Levels

The `AuthLevel` enum controls authentication for your routes:

| Level | Description |
|---|---|
| `NONE` | Public, no authentication required. |
| `SERVICE` | Requires a service-level or admin API token. Default. |
| `ADMIN` | Requires the master admin API token. |

## Database Access

Modules can use the Exposed ORM to create tables and run queries. The `Database` instance is shared with Nimbus core.

### Define Tables

```kotlin
import org.jetbrains.exposed.sql.Table

object MyRecords : Table("my_module_records") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128)
    val value = integer("value")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
```

### Create Tables

Use `DatabaseManager.createTables()` during init:

```kotlin
override suspend fun init(context: ModuleContext) {
    val db = context.service<DatabaseManager>()!!
    db.createTables(MyRecords)
}
```

### Run Queries

Use `transaction` with the database instance:

```kotlin
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

fun insertRecord(name: String, value: Int) {
    transaction(context.database) {
        MyRecords.insert {
            it[MyRecords.name] = name
            it[MyRecords.value] = value
            it[MyRecords.createdAt] = System.currentTimeMillis()
        }
    }
}
```

## Core Services

Use `getService()` or `service<T>()` to access core Nimbus components. Available services:

| Class | Description |
|---|---|
| `dev.kryonix.nimbus.event.EventBus` | Publish and subscribe to events. |
| `dev.kryonix.nimbus.database.DatabaseManager` | Database operations, table creation. |
| `dev.kryonix.nimbus.service.ServiceRegistry` | Query running services, get by name or group. |
| `dev.kryonix.nimbus.service.ServiceManager` | Start/stop services programmatically. Available after initial boot (lazy access in background loops). |
| `dev.kryonix.nimbus.group.GroupManager` | Access server group configurations. |
| `dev.kryonix.nimbus.config.NimbusConfig` | Read the main Nimbus configuration. |

::: warning
Accessing core types requires `compileOnly(project(":nimbus-core"))` in your build. Without it, use `getService()` with the fully qualified class name via reflection.
:::

Example using `EventBus`:

```kotlin
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.Events
import dev.kryonix.nimbus.module.service

override suspend fun init(context: ModuleContext) {
    val eventBus = context.service<EventBus>()!!

    eventBus.subscribe<Events.ServiceStarted> { event ->
        println("Service started: ${event.service.name}")
    }
}
```

## Building and Deploying

1. Build your module JAR:
   ```bash
   ./gradlew jar
   ```

2. Copy the JAR to the Nimbus `modules/` directory:
   ```bash
   cp build/libs/my-module.jar /path/to/nimbus/modules/
   ```

3. Restart Nimbus. The module is loaded automatically at startup.

Module load/enable status is logged to the console:
```
[INFO] Found 1 module JAR(s) in modules/
[INFO] Loaded module: My Module v1.0.0 (my-module)
[INFO] Initialized module: My Module
```

## Complete Example

A module that tracks service uptime with a console command, REST endpoint, and database table.

### UptimeModule.kt

```kotlin
package dev.example.uptime

import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.Events
import dev.kryonix.nimbus.module.*

class UptimeModule : NimbusModule {
    override val id = "uptime"
    override val name = "Uptime Tracker"
    override val version = "1.0.0"
    override val description = "Tracks service start/stop times"

    private lateinit var context: ModuleContext

    override suspend fun init(ctx: ModuleContext) {
        context = ctx

        // Create database table
        val db = ctx.service<DatabaseManager>()!!
        db.createTables(UptimeRecords)

        // Listen for service events
        val eventBus = ctx.service<EventBus>()!!
        eventBus.subscribe<Events.ServiceStarted> { event ->
            UptimeStore.recordStart(ctx.database, event.service.name)
        }
        eventBus.subscribe<Events.ServiceStopped> { event ->
            UptimeStore.recordStop(ctx.database, event.service.name)
        }

        // Register console command
        ctx.registerCommand(UptimeCommand(ctx.database))

        // Register API route
        ctx.registerRoutes({
            uptimeRoutes(ctx.database)
        }, AuthLevel.SERVICE)
    }

    override suspend fun enable() {}

    override fun disable() {}
}
```

### UptimeRecords.kt

```kotlin
package dev.example.uptime

import org.jetbrains.exposed.sql.Table

object UptimeRecords : Table("uptime_records") {
    val id = integer("id").autoIncrement()
    val serviceName = varchar("service_name", 64)
    val startedAt = long("started_at")
    val stoppedAt = long("stopped_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
```

### UptimeStore.kt

```kotlin
package dev.example.uptime

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object UptimeStore {
    fun recordStart(db: Database, name: String) {
        transaction(db) {
            UptimeRecords.insert {
                it[serviceName] = name
                it[startedAt] = System.currentTimeMillis()
            }
        }
    }

    fun recordStop(db: Database, name: String) {
        transaction(db) {
            UptimeRecords.update({
                (UptimeRecords.serviceName eq name) and UptimeRecords.stoppedAt.isNull()
            }) {
                it[stoppedAt] = System.currentTimeMillis()
            }
        }
    }

    fun getAll(db: Database): List<UptimeEntry> = transaction(db) {
        UptimeRecords.selectAll().map {
            UptimeEntry(
                it[UptimeRecords.serviceName],
                it[UptimeRecords.startedAt],
                it[UptimeRecords.stoppedAt]
            )
        }
    }
}

data class UptimeEntry(val serviceName: String, val startedAt: Long, val stoppedAt: Long?)
```

### UptimeCommand.kt

```kotlin
package dev.example.uptime

import dev.kryonix.nimbus.module.ModuleCommand
import org.jetbrains.exposed.sql.Database

class UptimeCommand(private val db: Database) : ModuleCommand {
    override val name = "uptime"
    override val description = "Show service uptime records"
    override val usage = "uptime [service]"

    override suspend fun execute(args: List<String>) {
        val records = UptimeStore.getAll(db)
        val filtered = if (args.isNotEmpty()) {
            records.filter { it.serviceName == args[0] }
        } else {
            records
        }

        if (filtered.isEmpty()) {
            println("No uptime records found.")
            return
        }

        for (record in filtered) {
            val duration = (record.stoppedAt ?: System.currentTimeMillis()) - record.startedAt
            val minutes = duration / 60_000
            val status = if (record.stoppedAt == null) "running" else "stopped"
            println("${record.serviceName}: ${minutes}m ($status)")
        }
    }
}
```

### UptimeRoutes.kt

```kotlin
package dev.example.uptime

import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Database

fun Route.uptimeRoutes(db: Database) {
    route("/api/uptime") {
        get {
            val records = UptimeStore.getAll(db)
            call.respond(records.map { mapOf(
                "service" to it.serviceName,
                "startedAt" to it.startedAt,
                "stoppedAt" to it.stoppedAt
            )})
        }
    }
}
```

### Resource Files

**`src/main/resources/META-INF/services/dev.kryonix.nimbus.module.NimbusModule`**
```
dev.example.uptime.UptimeModule
```

**`src/main/resources/module.properties`**
```properties
id=uptime
name=Uptime Tracker
description=Tracks service start/stop times
default=false
```

### Project Structure

```
my-uptime-module/
├── build.gradle.kts
└── src/main/
    ├── kotlin/dev/example/uptime/
    │   ├── UptimeModule.kt
    │   ├── UptimeRecords.kt
    │   ├── UptimeStore.kt
    │   ├── UptimeCommand.kt
    │   └── UptimeRoutes.kt
    └── resources/
        ├── module.properties
        └── META-INF/services/
            └── dev.kryonix.nimbus.module.NimbusModule
```
