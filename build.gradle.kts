import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DuplicatesStrategy
import java.math.BigDecimal

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    jacoco
}

group = "ru.ruscrafting"
version = "2.2.0"

repositories {
    mavenCentral()
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

    implementation(libs.custom.block.data)
    implementation(libs.bstats.bukkit)

    compileOnly(libs.coreprotect)
    compileOnly(libs.placeholder.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.placeholder.api)
    testRuntimeOnly(libs.coreprotect)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
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
    relocate("com.jeff_media.customblockdata", "ru.ruscrafting.trails.lib.customblockdata")
    relocate("org.bstats", "ru.ruscrafting.trails.lib.bstats")
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
                "ru/ruscrafting/trails/TrailsPlugin.class",
                "ru/ruscrafting/trails/config/YamlConfig.class",
                "ru/ruscrafting/trails/lib/customblockdata/CustomBlockData.class",
                "ru/ruscrafting/trails/lib/bstats/bukkit/Metrics.class",
            )
        check(entries.containsAll(required)) { "Deployable JAR is missing: ${required - entries}" }
        check(entries.none { it.startsWith("org/bukkit/") }) { "Paper API must not be shaded" }
        check(
            entries.none {
                it.startsWith("org/mockbukkit/") ||
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
        check(entries.none { it.startsWith("com/jeff_media/") || it.startsWith("org/bstats/") }) {
            "Runtime libraries must be relocated"
        }
        check(entries.none { it.startsWith("me/ccrama/") }) {
            "Legacy Java classes leaked into the artifact"
        }
        val descriptor = zipTree(jar).matching { include("plugin.yml") }.singleFile.readText()
        check("main: ru.ruscrafting.trails.TrailsPlugin" in descriptor)
        check("version: \"2.2.0\"" in descriptor)
        val mainClass = zipTree(jar).matching { include("ru/ruscrafting/trails/TrailsPlugin.class") }.singleFile.readBytes()
        val classMajorVersion = ((mainClass[6].toInt() and 0xff) shl 8) or (mainClass[7].toInt() and 0xff)
        check(classMajorVersion == 65) { "TrailsPlugin.class must target Java 21 (major 65), found $classMajorVersion" }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, verifyPluginArtifact)
}
