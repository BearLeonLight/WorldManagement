import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper

plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

val multiverseE2eRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

data class PaperDownload(val name: String, val url: String, val sha256: String)

val downloadConnectTimeoutMillis = 15_000
val downloadReadTimeoutMillis = 60_000

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun downloadPaperServer(version: String, channel: String): File {
    val userAgent = "WorldManagement-Gradle/${project.version} (https://github.com/BearL)"
    val buildsUrl = URI("https://fill.papermc.io/v3/projects/paper/versions/$version/builds").toURL()
    val connection = buildsUrl.openConnection().apply {
        connectTimeout = downloadConnectTimeoutMillis
        readTimeout = downloadReadTimeoutMillis
        setRequestProperty("User-Agent", userAgent)
    }
    val builds = connection.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        @Suppress("UNCHECKED_CAST")
        JsonSlurper().parse(reader) as List<Map<String, Any?>>
    }
    val build = builds.firstOrNull { it["channel"]?.toString()?.equals(channel, ignoreCase = true) == true }
        ?: throw GradleException("Paper $version has no $channel build. Set -PpaperDownloadChannel=STABLE|BETA|ALPHA or provide -PpaperServerJar.")
    @Suppress("UNCHECKED_CAST")
    val downloads = build["downloads"] as? Map<String, Map<String, Any?>>
        ?: throw GradleException("Paper downloads response has no downloads object for $version build ${build["id"]}.")
    val server = downloads["server:default"]
        ?: throw GradleException("Paper downloads response has no server:default artifact for $version build ${build["id"]}.")
    @Suppress("UNCHECKED_CAST")
    val checksums = server["checksums"] as? Map<String, String>
        ?: throw GradleException("Paper download has no checksums for $version build ${build["id"]}.")
    val artifact = PaperDownload(
        server["name"]?.toString() ?: throw GradleException("Paper download has no artifact name."),
        server["url"]?.toString() ?: throw GradleException("Paper download has no artifact URL."),
        checksums["sha256"] ?: throw GradleException("Paper download has no SHA-256 checksum.")
    )
    val cacheDirectory = File(gradle.gradleUserHomeDir, "caches/worldmanagement/paper/$version")
    val cachedJar = File(cacheDirectory, artifact.name)
    if (cachedJar.isFile && sha256(cachedJar).equals(artifact.sha256, ignoreCase = true)) {
        logger.lifecycle("Using cached Paper server JAR: ${cachedJar.absolutePath}")
        return cachedJar
    }
    cacheDirectory.mkdirs()
    val temporaryJar = File(cacheDirectory, artifact.name + ".part")
    logger.lifecycle("Downloading Paper ${build["id"]} ($channel) from ${artifact.url}")
    val artifactConnection = URI(artifact.url).toURL().openConnection().apply {
        connectTimeout = downloadConnectTimeoutMillis
        readTimeout = downloadReadTimeoutMillis
        setRequestProperty("User-Agent", userAgent)
    }
    artifactConnection.getInputStream().use { input -> temporaryJar.outputStream().use(input::copyTo) }
    val actualChecksum = sha256(temporaryJar)
    if (!actualChecksum.equals(artifact.sha256, ignoreCase = true)) {
        temporaryJar.delete()
        throw GradleException("Paper JAR SHA-256 mismatch: expected ${artifact.sha256}, got $actualChecksum.")
    }
    Files.move(
        temporaryJar.toPath(),
        cachedJar.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
    )
    return cachedJar
}

fun resolvePaperServerJar(): File {
    providers.gradleProperty("paperServerJar").orNull?.let { configured ->
        return file(configured).also { jar ->
            if (!jar.isFile) throw GradleException("Paper server JAR does not exist: ${jar.absolutePath}")
            logger.lifecycle("Using configured Paper server JAR: ${jar.absolutePath}")
        }
    }
    val source = providers.gradleProperty("paperServerSource").getOrElse("auto").lowercase()
    if (source !in setOf("auto", "local", "download")) {
        throw GradleException("paperServerSource must be auto, local, or download.")
    }
    val testServerDirectory = rootDir.parentFile.resolve("TestServer")
    val localJar = testServerDirectory.listFiles { candidate ->
        candidate.isFile && candidate.name.matches(Regex("paper-.+\\.jar", RegexOption.IGNORE_CASE))
    }?.maxByOrNull(File::lastModified)
    if (source != "download" && localJar != null) {
        logger.lifecycle("Using TestServer Paper JAR: ${localJar.absolutePath}")
        return localJar
    }
    if (source == "local") {
        throw GradleException("No Paper server JAR found in ${testServerDirectory.absolutePath}.")
    }
    val minecraftVersion = providers.gradleProperty("paperApiVersionDeclaration").get()
    val apiVersion = providers.gradleProperty("paperApiVersion").get().lowercase()
    val defaultChannel = when {
        apiVersion.contains("-alpha") -> "ALPHA"
        apiVersion.contains("-beta") -> "BETA"
        else -> "STABLE"
    }
    val channel = providers.gradleProperty("paperDownloadChannel").getOrElse(defaultChannel).uppercase()
    return downloadPaperServer(minecraftVersion, channel)
}

fun org.gradle.api.tasks.Exec.configureLuckPermsEnvironment() {
    project.providers.gradleProperty("luckPermsPluginJar").orNull?.let { configured ->
        val jar = project.file(configured)
        if (!jar.isFile) {
            throw GradleException("LuckPerms plugin JAR does not exist: ${jar.absolutePath}")
        }
        environment("WM_LUCKPERMS_PLUGIN_JAR", jar.absolutePath)
    } ?: project.rootDir.parentFile.resolve("LuckPerms/bukkit/loader/build/libs")
        .listFiles { candidate ->
            candidate.isFile && candidate.name.matches(Regex("LuckPerms-Bukkit-.+\\.jar", RegexOption.IGNORE_CASE))
        }
        ?.maxByOrNull(File::lastModified)
        ?.let { environment("WM_LUCKPERMS_PLUGIN_JAR", it.absolutePath) }
    environment("WM_LUCKPERMS_CACHE_DIR", File(project.gradle.gradleUserHomeDir, "caches/worldmanagement/luckperms").absolutePath)
    environment("WM_LUCKPERMS_FILE_NAME", "LuckPerms-Bukkit-5.5.53.jar")
    environment("WM_LUCKPERMS_URL", "https://cdn.modrinth.com/data/Vebnzrzj/versions/MBSY8toc/LuckPerms-Bukkit-5.5.53.jar")
    environment(
        "WM_LUCKPERMS_SHA512",
        "a0e087adfc1c7b9fab8fdb5a430a3331a2ca30bfc72818bb7e65ce9baff051a1490834be8cbb9cbeda292f2e92c44cf03fb829ebeb233ade9d666ed908e49ad5"
    )
}

fun org.gradle.api.tasks.Exec.configureMultiverseEnvironment() {
    project.providers.gradleProperty("multiversePluginJar").orNull?.let { configured ->
        val jar = project.file(configured)
        if (!jar.isFile) {
            throw GradleException("Multiverse-Core plugin JAR does not exist: ${jar.absolutePath}")
        }
        environment("WM_MULTIVERSE_PLUGIN_JAR", jar.absolutePath)
    } ?: multiverseE2eRuntime.singleFile.also { jar ->
        val expectedChecksum = "c91a7c2c25ad7d878257b08c980381e8b5ffba8df63faf402242bfb56a058884"
        val actualChecksum = sha256(jar)
        if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
            throw GradleException(
                "Multiverse-Core 5.7.3 JAR SHA-256 mismatch: expected $expectedChecksum, got $actualChecksum."
            )
        }
        environment("WM_MULTIVERSE_PLUGIN_JAR", jar.absolutePath)
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.onarandombox.com/multiverse-releases")
    maven("https://repo.helpch.at/releases/")
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    val paperApi = "io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}"
    compileOnly(paperApi)
    testImplementation(paperApi)
    implementation("dev.dejvokep:boosted-yaml:${providers.gradleProperty("boostedYamlVersion").get()}")
    implementation("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqliteJdbcVersion").get()}")
    implementation("com.mysql:mysql-connector-j:${providers.gradleProperty("mysqlConnectorVersion").get()}")
    implementation("org.mariadb.jdbc:mariadb-java-client:${providers.gradleProperty("mariaDbConnectorVersion").get()}")
    implementation("com.zaxxer:HikariCP:${providers.gradleProperty("hikariVersion").get()}")
    compileOnly("net.luckperms:api:${providers.gradleProperty("luckPermsApiVersion").get()}")
    testImplementation("net.luckperms:api:${providers.gradleProperty("luckPermsApiVersion").get()}")
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:${providers.gradleProperty("multiverseCoreVersion").get()}")
    multiverseE2eRuntime("org.mvplugins.multiverse.core:multiverse-core:${providers.gradleProperty("multiverseCoreVersion").get()}")
    compileOnly("me.clip:placeholderapi:${providers.gradleProperty("placeholderApiVersion").get()}")
    testImplementation("me.clip:placeholderapi:${providers.gradleProperty("placeholderApiVersion").get()}")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:${providers.gradleProperty("miniPlaceholdersVersion").get()}")
    testImplementation("io.github.miniplaceholders:miniplaceholders-api:${providers.gradleProperty("miniPlaceholdersVersion").get()}")
    compileOnly("net.kyori:adventure-text-serializer-ansi:5.2.0")
    compileOnly("net.kyori:ansi:1.1.1")
    implementation("net.kyori:adventure-nbt:5.2.0")
    testRuntimeOnly("net.kyori:adventure-text-serializer-ansi:5.2.0")
    testRuntimeOnly("net.kyori:ansi:1.1.1")

    testImplementation(platform("org.junit:junit-bom:${providers.gradleProperty("junitVersion").get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    create("e2eSupport")
}

dependencies {
    add(
        "e2eSupportCompileOnly",
        "io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}"
    )
    add(
        "e2eSupportCompileOnly",
        "net.luckperms:api:${providers.gradleProperty("luckPermsApiVersion").get()}"
    )
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    val verifyProjectLayout = register("verifyProjectLayout") {
        group = "verification"
        description = "Rejects build descriptors that do not belong in the WorldManagement project root."

        doLast {
            val forbiddenFiles = listOf(
                rootProject.file("pom.xml") to "WorldManagement uses build.gradle.kts, not Maven.",
                rootProject.file("plugin.yml") to "The only plugin descriptor is src/main/resources/paper-plugin.yml.",
            )
            val violations = forbiddenFiles
                .filter { (file, _) -> file.exists() }
                .joinToString(System.lineSeparator()) { (file, reason) -> "- ${file.name}: $reason" }
            if (violations.isNotEmpty()) {
                throw GradleException("Unexpected files in the project root:${System.lineSeparator()}$violations")
            }
        }
    }

    named("check") {
        dependsOn(verifyProjectLayout)
    }

    processResources {
        val properties = mapOf(
            "version" to project.version,
            "apiVersion" to providers.gradleProperty("paperApiVersionDeclaration").get(),
        )
        inputs.properties(properties)
        filesMatching("paper-plugin.yml") {
            expand(properties)
        }
    }

    named<ProcessResources>("processE2eSupportResources") {
        val properties = mapOf(
            "version" to project.version,
            "apiVersion" to providers.gradleProperty("paperApiVersionDeclaration").get(),
        )
        inputs.properties(properties)
        filesMatching("paper-plugin.yml") {
            expand(properties)
        }
    }

    test {
        useJUnitPlatform()
        systemProperty("junit.jupiter.execution.timeout.default", "10 s")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        timeout.set(Duration.ofMinutes(3))
    }

    val e2eSupportJar = register<Jar>("e2eSupportJar") {
        group = "verification"
        description = "Builds the isolated Paper support plugin used only by console and player E2E tests."
        archiveBaseName = "WorldManagement-E2E-Support"
        archiveClassifier = ""
        from(sourceSets["e2eSupport"].output)
    }

    val verifyE2eSupportIsolation = register("verifyE2eSupportIsolation") {
        group = "verification"
        description = "Verifies that E2E support classes are present only in the isolated support JAR."
        dependsOn(shadowJar, e2eSupportJar)

        doLast {
            val supportPath = "io/github/bearl/worldmanagement/e2esupport/"
            val productionEntries = zipTree(shadowJar.get().archiveFile.get().asFile)
                .matching { include("$supportPath**") }
                .files
            if (productionEntries.isNotEmpty()) {
                throw GradleException("Production plugin JAR contains E2E support classes.")
            }
            val supportEntries = zipTree(e2eSupportJar.get().archiveFile.get().asFile)
                .matching { include("$supportPath**") }
                .files
            if (supportEntries.isEmpty()) {
                throw GradleException("E2E support JAR contains no support classes.")
            }
        }
    }

    named("check") {
        dependsOn(verifyE2eSupportIsolation, "verifyJdbcDriverServices")
    }

    shadowJar {
        archiveClassifier = ""
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()
        relocate("dev.dejvokep.boostedyaml", "io.github.bearl.worldmanagement.lib.boostedyaml")
        relocate("org.yaml.snakeyaml", "io.github.bearl.worldmanagement.lib.snakeyaml")
    }

    register("verifyJdbcDriverServices") {
        group = "verification"
        description = "Verifies that the deployable JAR exposes every bundled JDBC driver."
        dependsOn(shadowJar)

        doLast {
            val servicePath = "META-INF/services/java.sql.Driver"
            val serviceFiles = zipTree(shadowJar.get().archiveFile.get().asFile)
                .matching { include(servicePath) }
                .files
            if (serviceFiles.size != 1) {
                throw GradleException("Production plugin JAR must contain exactly one $servicePath descriptor.")
            }
            val drivers = serviceFiles.single().readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
            val expected = setOf(
                "org.sqlite.JDBC",
                "com.mysql.cj.jdbc.Driver",
                "org.mariadb.jdbc.Driver",
            )
            val missing = expected - drivers
            if (missing.isNotEmpty()) {
                throw GradleException("Production plugin JAR is missing JDBC service providers: ${missing.sorted()}.")
            }
        }
    }

    build {
        dependsOn(shadowJar)
    }

    register<Delete>("cleanE2eDependencies") {
        group = "build setup"
        description = "Removes the reproducible Node dependencies used by console and player E2E tests."
        delete(
            layout.projectDirectory.dir("e2e/console/node_modules"),
            layout.projectDirectory.dir("e2e/player/node_modules")
        )
    }

    register<Delete>("cleanWorldManagementE2eCache") {
        group = "build setup"
        description = "Removes WorldManagement's checksum-verified Paper, Via, LuckPerms, and placeholder test downloads."
        delete(File(gradle.gradleUserHomeDir, "caches/worldmanagement"))
    }

    register("paperJarSmokeTest") {
        group = "verification"
        description = "Starts an isolated Paper server with the shaded plugin JAR and verifies WorldManagement loads its metadata."
        dependsOn(shadowJar)
        timeout.set(Duration.ofMinutes(3))

        doLast {
            val serverJar = resolvePaperServerJar()
            val placeholderApiJar = providers.gradleProperty("placeholderApiPluginJar").orNull?.let(::file)
            val miniPlaceholdersJar = providers.gradleProperty("miniPlaceholdersPluginJar").orNull?.let(::file)
            val multiverseSmokeJar = providers.gradleProperty("smokeMultiversePluginJar").orNull?.let(::file)
            val expectedMultiverseStatus = providers.gradleProperty("expectedSmokeMultiverseStatus").orNull
            if ((placeholderApiJar == null) != (miniPlaceholdersJar == null)) {
                throw GradleException(
                    "placeholderApiPluginJar and miniPlaceholdersPluginJar must be provided together."
                )
            }
            if (expectedMultiverseStatus != null && multiverseSmokeJar == null) {
                throw GradleException(
                    "expectedSmokeMultiverseStatus requires smokeMultiversePluginJar."
                )
            }
            listOfNotNull(placeholderApiJar, miniPlaceholdersJar, multiverseSmokeJar).forEach { jar ->
                if (!jar.isFile) throw GradleException("Smoke dependency plugin JAR does not exist: ${jar.absolutePath}")
            }
            val verifyPlaceholderProviders = placeholderApiJar != null

            val smokeDirectory = layout.buildDirectory.dir("paper-jar-smoke").get().asFile
            if (serverJar.toPath().toAbsolutePath().normalize().startsWith(smokeDirectory.toPath().toAbsolutePath().normalize())) {
                throw GradleException("paperServerJar must be outside ${smokeDirectory.absolutePath}, because the smoke task recreates that directory.")
            }
            delete(smokeDirectory)
            val pluginsDirectory = File(smokeDirectory, "plugins")
            check(pluginsDirectory.mkdirs()) { "Could not create smoke-test plugin directory." }
            val pluginDataDirectory = File(pluginsDirectory, "WorldManagement")
            check(pluginDataDirectory.mkdirs()) { "Could not create smoke-test plugin data directory." }
            copy {
                from(serverJar)
                into(smokeDirectory)
                rename { "paper.jar" }
            }
            copy {
                from(shadowJar.get().archiveFile)
                into(pluginsDirectory)
            }
            if (verifyPlaceholderProviders) {
                copy {
                    from(placeholderApiJar, miniPlaceholdersJar)
                    into(pluginsDirectory)
                }
            }
            if (multiverseSmokeJar != null) {
                copy {
                    from(multiverseSmokeJar)
                    into(pluginsDirectory)
                }
            }
            File(smokeDirectory, "eula.txt").writeText("eula=true\n")
            File(smokeDirectory, "server.properties").writeText(
                """
                server-port=0
                online-mode=false
                spawn-protection=0
                view-distance=2
                simulation-distance=2
                """.trimIndent() + "\n"
            )
                        File(pluginDataDirectory, "config.yml").writeText(
                                """
                                schema-version: 1
                                storage:
                                    provider: SQLITE
                                    jdbc-url: "jdbc:sqlite:plugins/WorldManagement/metadata.db"
                                    username: ""
                                    password: ""
                                """.trimIndent() + "\n"
                        )

            val logFile = File(smokeDirectory, "latest.log")
            val javaExecutable = File(System.getProperty("java.home"), "bin/java.exe")
                .takeIf(File::isFile) ?: File(System.getProperty("java.home"), "bin/java")
            val process = ProcessBuilder(
                javaExecutable.absolutePath,
                "-Xms512M",
                "-Xmx512M",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-jar",
                "paper.jar",
                "--nogui"
            ).directory(smokeDirectory)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()

            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                var pluginReady = false
                while (process.isAlive && System.nanoTime() < deadline) {
                    if (logFile.isFile && logFile.readText().replace(Regex("\\u001B\\[[\\d;]*[^\\d;]"), "")
                        .contains("[WorldManagement] Enabled WorldManagement")) {
                        pluginReady = true
                        break
                    }
                    Thread.sleep(250)
                }
                if (!pluginReady) {
                    throw GradleException("Paper JAR smoke test did not fully enable WorldManagement. See ${logFile.absolutePath}")
                }

                if (verifyPlaceholderProviders) {
                    val startupLog = logFile.readText()
                    val unavailableProvider = listOf(
                        "PlaceholderAPI: available",
                        "MiniPlaceholders: available"
                    ).firstOrNull { marker -> !startupLog.contains(marker) }
                    if (unavailableProvider != null) {
                        throw GradleException(
                            "Paper JAR smoke test did not register '$unavailableProvider'. See ${logFile.absolutePath}"
                        )
                    }
                }
                if (expectedMultiverseStatus != null) {
                    val startupLog = logFile.readText()
                    val marker = "Multiverse-Core: $expectedMultiverseStatus"
                    if (!startupLog.contains(marker)) {
                        throw GradleException(
                            "Paper JAR smoke test did not report '$marker'. See ${logFile.absolutePath}"
                        )
                    }
                    if (startupLog.contains("NoClassDefFoundError")) {
                        throw GradleException(
                            "Paper JAR smoke test leaked a Multiverse linkage error. See ${logFile.absolutePath}"
                        )
                    }
                }

                val output = process.outputStream.bufferedWriter()
                output.write("wm list\n")
                if (verifyPlaceholderProviders) {
                    output.write("papi parse --null PAPI-WM=%wm_managed_world_count%=END\n")
                    output.write("papi parse --null PAPI-WORLD=%wm_world_exists:missing%=END\n")
                    output.write("miniplaceholders parse --null MINI-WM=<wm_managed_world_count>=END\n")
                    output.write("miniplaceholders parse --null MINI-WORLD=<wm_world_exists:missing>=END\n")
                }
                output.flush()
                val commandDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var commandHandled = false
                while (process.isAlive && System.nanoTime() < commandDeadline) {
                    val commandLog = logFile.readText()
                    val wmListHandled = commandLog.contains("目前沒有受 WorldManagement 管理的世界。")
                    val placeholdersHandled = !verifyPlaceholderProviders || listOf(
                        "PAPI-WM=0=END",
                        "PAPI-WORLD=false=END",
                        "MINI-WM=0=END",
                        "MINI-WORLD=false=END"
                    ).all(commandLog::contains)
                    if (wmListHandled && placeholdersHandled) {
                        commandHandled = true
                        break
                    }
                    Thread.sleep(100)
                }
                if (!commandHandled) {
                    output.close()
                    throw GradleException(
                        "Paper JAR smoke test did not observe every command result. See ${logFile.absolutePath}"
                    )
                }

                output.use {
                    output.write("stop\n")
                    output.flush()
                }
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    throw GradleException("Paper JAR smoke test server did not stop. See ${logFile.absolutePath}")
                }
                if (process.exitValue() != 0) {
                    throw GradleException("Paper JAR smoke test exited with ${process.exitValue()}. See ${logFile.absolutePath}")
                }
                val shutdownLog = logFile.readText()
                if (!shutdownLog.contains("WorldManagement terminal shutdown complete.")) {
                    throw GradleException("Paper JAR smoke test exited before WorldManagement terminal shutdown completed. See ${logFile.absolutePath}")
                }
                val shutdownFailure = listOf("zip file error", "I/O shutdown failed", "did not drain")
                    .firstOrNull(shutdownLog::contains)
                if (shutdownFailure != null) {
                    throw GradleException("Paper JAR smoke test reported shutdown failure '$shutdownFailure'. See ${logFile.absolutePath}")
                }
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    val interrupted = Thread.interrupted()
                    try {
                        if (!process.waitFor(10, TimeUnit.SECONDS)) {
                            throw GradleException("Paper JAR smoke test process survived forced termination. See ${logFile.absolutePath}")
                        }
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }
    }

    register<Exec>("installPlayerE2eDependencies") {
        group = "verification"
        description = "Installs the optional Mineflayer player E2E dependencies."
        workingDir(layout.projectDirectory.dir("e2e/player"))
        val npmExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
        commandLine(
            npmExecutable, "ci", "--ignore-scripts", "--no-audit", "--no-fund",
            "--fetch-timeout=60000", "--fetch-retries=1"
        )
        timeout.set(Duration.ofMinutes(3))
    }

    register<Exec>("installConsoleE2eDependencies") {
        group = "verification"
        description = "Installs the isolated console E2E dependencies."
        workingDir(layout.projectDirectory.dir("e2e/console"))
        val npmExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
        commandLine(
            npmExecutable, "ci", "--ignore-scripts", "--no-audit", "--no-fund",
            "--fetch-timeout=60000", "--fetch-retries=1"
        )
        timeout.set(Duration.ofMinutes(3))
    }

    register<Exec>("playerE2eStrategyTest") {
        group = "verification"
        description = "Verifies native Mineflayer protocol selection and conditional Via fallback without starting Paper."
        dependsOn("installPlayerE2eDependencies")
        workingDir(layout.projectDirectory)
        commandLine(
            "node", "--test",
            "e2e/player/test/command-tree.test.cjs",
            "e2e/player/test/output-monitor.test.cjs",
            "e2e/player/test/protocol-strategy.test.cjs"
        )
        timeout.set(Duration.ofMinutes(1))
    }

    register<Exec>("commandRuntimeHarnessTest") {
        group = "verification"
        description = "Verifies command runtime leaf mapping and destructive test-directory guards without starting Paper."
        workingDir(layout.projectDirectory)
        commandLine(
            "node", "--test",
            "e2e/build-child-path.test.cjs",
            "e2e/process-control.test.cjs",
            "e2e/runtime-coverage.test.cjs",
            "e2e/shutdown-log.test.cjs"
        )
        timeout.set(Duration.ofMinutes(1))
    }

    val preparedPaperServerJar = objects.fileProperty()
    val preparePaperE2eServerJar = register("preparePaperE2eServerJar") {
        group = "verification"
        description = "Resolves the checksum-verified Paper server JAR before starting an E2E runner."
        timeout.set(Duration.ofMinutes(3))
        doLast {
            preparedPaperServerJar.set(resolvePaperServerJar())
        }
    }

    register<Exec>("paperConsoleCommandTest") {
        group = "verification"
        description = "Runs the Paper console command matrix without starting a Mineflayer player client."
        dependsOn(
            shadowJar, e2eSupportJar, "commandRuntimeHarnessTest",
            "installConsoleE2eDependencies", preparePaperE2eServerJar
        )
        workingDir(layout.projectDirectory)
        commandLine("node", "e2e/console/test/console-command-test.cjs")
        timeout.set(Duration.ofMinutes(7))
        doFirst {
            val javaExecutable = File(System.getProperty("java.home"), "bin/java.exe")
                .takeIf(File::isFile) ?: File(System.getProperty("java.home"), "bin/java")
            environment("WM_PAPER_JAR", preparedPaperServerJar.get().asFile.absolutePath)
            environment("WM_PLUGIN_JAR", shadowJar.get().archiveFile.get().asFile.absolutePath)
            environment("WM_E2E_SUPPORT_JAR", e2eSupportJar.get().archiveFile.get().asFile.absolutePath)
            environment("WM_JAVA_EXECUTABLE", javaExecutable.absolutePath)
            environment("WM_CONSOLE_TEST_SERVER_DIR", layout.buildDirectory.dir("console-command-test").get().asFile.absolutePath)
            configureLuckPermsEnvironment()
            configureMultiverseEnvironment()
        }
    }

    register<Exec>("paperPlayerE2eTest") {
        group = "verification"
        description = "Runs the optional Mineflayer player-side black-box test against an isolated Paper server."
        dependsOn(
            shadowJar, e2eSupportJar, "commandRuntimeHarnessTest",
            "playerE2eStrategyTest", preparePaperE2eServerJar
        )
        workingDir(layout.projectDirectory)
        commandLine("node", "e2e/player/test/player-smoke.cjs")
        timeout.set(Duration.ofMinutes(11))
        doFirst {
            val javaExecutable = File(System.getProperty("java.home"), "bin/java.exe")
                .takeIf(File::isFile) ?: File(System.getProperty("java.home"), "bin/java")
            environment("WM_PAPER_JAR", preparedPaperServerJar.get().asFile.absolutePath)
            environment("WM_PLUGIN_JAR", shadowJar.get().archiveFile.get().asFile.absolutePath)
            environment("WM_E2E_SUPPORT_JAR", e2eSupportJar.get().archiveFile.get().asFile.absolutePath)
            environment("WM_JAVA_EXECUTABLE", javaExecutable.absolutePath)
            environment("WM_E2E_SERVER_DIR", layout.buildDirectory.dir("player-e2e").get().asFile.absolutePath)
            val viaVersion = providers.gradleProperty("viaVersionVersion").get()
            val viaBackwardsVersion = providers.gradleProperty("viaBackwardsVersion").get()
            environment("WM_VIA_CACHE_DIR", File(gradle.gradleUserHomeDir, "caches/worldmanagement/via").absolutePath)
            environment("WM_VIAVERSION_FILE_NAME", "ViaVersion-$viaVersion.jar")
            environment("WM_VIAVERSION_URL", "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/$viaVersion/PAPER/ViaVersion-$viaVersion.jar")
            environment("WM_VIAVERSION_SHA256", providers.gradleProperty("viaVersionSha256").get())
            environment("WM_VIABACKWARDS_FILE_NAME", "ViaBackwards-$viaBackwardsVersion.jar")
            environment("WM_VIABACKWARDS_URL", "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaBackwards/versions/$viaBackwardsVersion/PAPER/ViaBackwards-$viaBackwardsVersion.jar")
            environment("WM_VIABACKWARDS_SHA256", providers.gradleProperty("viaBackwardsSha256").get())
            configureLuckPermsEnvironment()
            providers.gradleProperty("mineflayerMinecraftVersion").orNull?.let {
                environment("WM_MINECRAFT_VERSION", it)
            }
        }
    }
}
