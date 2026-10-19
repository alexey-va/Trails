import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DuplicatesStrategy
import java.math.BigDecimal
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    jacoco
    id("io.github.drownek.plugwright") version "2.0.4"
}

group = "ru.ruscrafting"
version = "2.3.19"

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        name = "RusCrafting"
        content { includeGroup("ru.ruscrafting.arc") }
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
        content {
            includeGroup("io.papermc.paper")
            includeGroup("io.papermc")
            includeGroup("com.mojang")
            includeGroup("net.md-5")
        }
    }
    maven("https://repo.extendedclip.com/releases/") {
        name = "PlaceholderAPI"
        content { includeGroup("me.clip") }
    }
    maven("https://maven.playpro.com/") {
        name = "CoreProtect"
        content { includeGroup("net.coreprotect") }
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.41")

    implementation(libs.bstats.bukkit)
    implementation(libs.arc.core.paper) {
        exclude(group = "net.kyori")
        exclude(group = "org.slf4j")
        exclude(group = "org.snakeyaml")
    }

    compileOnly(libs.coreprotect)
    compileOnly(libs.placeholder.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.arc.core.paper.testing)
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.41")
    testImplementation(libs.placeholder.api)
    testRuntimeOnly(libs.coreprotect)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-jvm-default=enable", "-Xjsr305=strict")
        allWarningsAsErrors.set(true)
    }
}

tasks.processResources {
    val properties = mapOf("version" to project.version.toString())
    inputs.properties(properties)
    filesMatching("plugin.yml") { expand(properties) }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = BigDecimal("0.80")
            }
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    filesMatching("META-INF/LICENSE-arc-core.txt") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    relocate("org.bstats", "ru.ruscrafting.trails.lib.bstats")
    relocate("ru.arc", "ru.ruscrafting.trails.lib.arc") {
        exclude("ru.arc.paper.api.**")
    }
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("plain")
}

val apiJar = tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Builds the Java-only Trails API contract for compileOnly consumers."
    dependsOn(tasks.classes)
    archiveBaseName.set("trails-api")
    from(sourceSets.main.get().output) { include("ru/ruscrafting/trails/api/**") }
    from("src/main/resources/META-INF/licenses/Trails-UNLICENSE.txt") { into("META-INF/licenses") }
}

val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
    group = "build"
    archiveBaseName.set("trails-api")
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource) { include("ru/ruscrafting/trails/api/**") }
    from("src/main/resources/META-INF/licenses/Trails-UNLICENSE.txt") { into("META-INF/licenses") }
}

val verifyFootTrafficApi = tasks.register("verifyFootTrafficApi") {
    group = "verification"
    dependsOn(apiJar, apiSourcesJar, tasks.shadowJar)
    doLast {
        val api = zipTree(apiJar.get().archiveFile)
        val runtime = zipTree(tasks.shadowJar.get().archiveFile)
        val classes = setOf("ru/ruscrafting/trails/api/TrailsFootTrafficService.class",
            "ru/ruscrafting/trails/api/TrailsFootTrafficService\$StepResult.class")
        val entries = mutableSetOf<String>()
        api.visit { if (!isDirectory && relativePath.pathString.endsWith(".class")) entries += relativePath.pathString }
        check(entries == classes) { "Trails API must contain only the Java contract: $entries" }
        for (entry in classes) {
            val contract = api.matching { include(entry) }.singleFile.readBytes()
            val provider = runtime.matching { include(entry) }.singleFile.readBytes()
            check(contract.contentEquals(provider)) { "Trails runtime/API class mismatch: $entry" }
            check(((contract[6].toInt() and 0xff) shl 8) or (contract[7].toInt() and 0xff) == 69) {
                "Trails API must target Java 25: $entry"
            }
            check(contract.toString(Charsets.ISO_8859_1).let { "kotlin/" !in it && "ru/arc/" !in it }) {
                "Trails API must not expose a plugin-local Kotlin or Core runtime: $entry"
            }
        }
    }
}

tasks.assemble { dependsOn(apiJar, apiSourcesJar) }

tasks.build {
    dependsOn(tasks.shadowJar)
}

val verifyPluginArtifact = tasks.register("verifyPluginArtifact") {
    group = "verification"
    description = "Verifies the deployable Trails shadow JAR contract."
    dependsOn(tasks.shadowJar, verifyFootTrafficApi)
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = mutableSetOf<String>()
        zipTree(jar).visit {
            if (!isDirectory) entries += relativePath.pathString
        }
        val required =
            setOf(
                "plugin.yml",
                "config.yml",
                "trails.yml",
                "roads.yml",
                "lang/en-US.yml",
                "lang/ru-RU.yml",
                "lang/zh-CN.yml",
                "THIRD_PARTY_NOTICES.txt",
                "META-INF/licenses/Apache-2.0.txt",
                "META-INF/licenses/bStats-MIT.txt",
                "META-INF/licenses/Trails-UNLICENSE.txt",
                "ru/ruscrafting/trails/TrailsPlugin.class",
                "ru/ruscrafting/trails/config/YamlConfig.class",
                "ru/ruscrafting/trails/lib/bstats/bukkit/Metrics.class",
                "ru/ruscrafting/trails/lib/arc/paper/runtime/PaperPluginRuntime.class",
            )
        check(entries.containsAll(required)) { "Deployable JAR is missing: ${required - entries}" }
        check(entries.none { it.startsWith("org/bukkit/") }) { "Paper API must not be shaded" }
        check(
            entries.none {
                it.startsWith("net/kyori/") ||
                    it.startsWith("org/slf4j/") ||
                    it.startsWith("org/snakeyaml/")
            },
        ) {
            "Paper-provided libraries must not be shaded"
        }
        check(
            entries.none {
                it.startsWith("org/mockbukkit/") ||
                    it.startsWith("ru/arc/") ||
                    it.startsWith("io/kotest/") ||
                    it.startsWith("io/mockk/") ||
                    it.startsWith("org/junit/")
            },
        ) {
            "Test frameworks must not be shaded"
        }
        check(
            entries.none {
                it.startsWith("net/coreprotect/") ||
                    it.startsWith("me/clip/")
            },
        ) {
            "Optional plugin APIs must not be shaded"
        }
        check(entries.none { it.startsWith("org/bstats/") }) {
            "Runtime libraries must be relocated"
        }
        check(entries.none { it.startsWith("me/ccrama/") }) {
            "Legacy Java classes leaked into the artifact"
        }
        val descriptor = zipTree(jar).matching { include("plugin.yml") }.singleFile.readText()
        check("main: ru.ruscrafting.trails.TrailsPlugin" in descriptor)
        check("version: \"${project.version}\"" in descriptor)
        check("- ARC" in descriptor) { "Optional ARC telemetry requires dependency classloader visibility" }
        val telemetryBytecode = zipTree(jar).matching {
            include("ru/ruscrafting/trails/integration/ArcProductTelemetry\$AvailableArcTelemetry.class")
        }.singleFile.readBytes().toString(Charsets.ISO_8859_1)
        check("ru/arc/paper/api/ArcTelemetryProvider" in telemetryBytecode) {
            "Shadow relocated the shared ARC telemetry contract"
        }
        val notices = zipTree(jar).matching { include("THIRD_PARTY_NOTICES.txt") }.singleFile.readText()
        listOf("Kotlin standard library 2.4.10", "JetBrains Java annotations 13.0", "arc-core and arc-core-paper 2.7.41", "bStats base and Bukkit 3.2.1")
            .forEach { dependency -> check(dependency in notices) { "THIRD_PARTY_NOTICES is missing $dependency" } }
        val shadedRuntimeVersions =
            configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.associate { artifact ->
                "${artifact.moduleVersion.id.group}:${artifact.name}" to artifact.moduleVersion.id.version
            }
        mapOf(
            "org.jetbrains.kotlin:kotlin-stdlib" to "2.4.10",
            "org.jetbrains:annotations" to "13.0",
            "org.bstats:bstats-base" to "3.2.1",
            "org.bstats:bstats-bukkit" to "3.2.1",
            "ru.ruscrafting.arc:arc-core" to "2.7.41",
            "ru.ruscrafting.arc:arc-core-paper" to "2.7.41",
        ).forEach { (module, expectedVersion) ->
            check(shadedRuntimeVersions[module] == expectedVersion) {
                "Shaded runtime inventory mismatch for $module: expected $expectedVersion, found ${shadedRuntimeVersions[module]}"
            }
        }
        val apacheLicense = zipTree(jar).matching { include("META-INF/licenses/Apache-2.0.txt") }.singleFile.readText()
        check("Apache License" in apacheLicense && "Version 2.0, January 2004" in apacheLicense)
        val bstatsLicense = zipTree(jar).matching { include("META-INF/licenses/bStats-MIT.txt") }.singleFile.readText()
        check("Copyright (c) 2021 Bastian Oppermann" in bstatsLicense && "Permission is hereby granted" in bstatsLicense)
        val mainClass = zipTree(jar).matching { include("ru/ruscrafting/trails/TrailsPlugin.class") }.singleFile.readBytes()
        val classMajorVersion = ((mainClass[6].toInt() and 0xff) shl 8) or (mainClass[7].toInt() and 0xff)
        check(classMajorVersion == 69) { "TrailsPlugin.class must target Java 25 (major 69), found $classMajorVersion" }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, verifyPluginArtifact)
}

// E2E runs separately from check: a disposable real Paper server and Mineflayer clients.
val e2eArcCoreJar = providers.gradleProperty("e2eArcCoreJar").map { file(it) }
val verifyE2eProvider = tasks.register("verifyE2eProvider") {
    inputs.file(e2eArcCoreJar).optional()
    doLast {
        val provider = requireNotNull(e2eArcCoreJar.orNull) {
            "Supply -Pe2eArcCoreJar=/absolute/path/ArcCore-0.1.13.jar for the real Paper fixture"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(provider.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == "952cca75616a433f7ee02ea0a0d078b4b88291dafd62e7acb415be3952dbef10") {
            "ArcCore 0.1.13 fixture checksum mismatch: $digest"
        }
    }
}
tasks.withType<me.drownek.plugwright.AbstractPlugwrightTask>().configureEach { dependsOn(verifyE2eProvider) }

plugwright {
    minecraftVersion.set("1.21.11")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        e2eArcCoreJar.orNull?.let { file("plugins/ArcCore.jar", it) }
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/Trails/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml"))
        file("plugins/Trails/roads.yml", projectDir.resolve("src/main/resources/roads.yml").readText()
            .replaceFirst("enabled: false", "enabled: true"))
    }
}
