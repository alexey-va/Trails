import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DuplicatesStrategy
import java.math.BigDecimal

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    jacoco
}

group = "ru.ruscrafting"
version = "2.3.0"

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
    filesMatching("META-INF/LICENSE-arc-core.txt") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    relocate("org.bstats", "ru.ruscrafting.trails.lib.bstats")
    relocate("ru.arc", "ru.ruscrafting.trails.lib.arc")
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

val verifyPluginArtifact = tasks.register("verifyPluginArtifact") {
    group = "verification"
    description = "Verifies the deployable Trails shadow JAR contract."
    dependsOn(tasks.shadowJar)
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
        check("version: \"2.3.0\"" in descriptor)
        val notices = zipTree(jar).matching { include("THIRD_PARTY_NOTICES.txt") }.singleFile.readText()
        listOf("Kotlin standard library 2.4.10", "JetBrains Java annotations 13.0", "arc-core and arc-core-paper 2.0.2", "bStats base and Bukkit 3.2.1")
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
            "ru.ruscrafting.arc:arc-core" to "2.0.2",
            "ru.ruscrafting.arc:arc-core-paper" to "2.0.2",
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
