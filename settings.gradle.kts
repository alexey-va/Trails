pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "Trails"

providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) { "arcCoreDir must point to an arc-core checkout" }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            substitute(module("ru.ruscrafting.arc:arc-core-paper-api")).using(project(":arc-core-paper-api"))
        }
    }
}
