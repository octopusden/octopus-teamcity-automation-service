pluginManagement {
    plugins {
        id("org.springframework.boot") version (extra["spring-boot-plugin.version"] as String)
        id("io.spring.dependency-management") version "1.1.7"
        val kotlinPluginVersion = providers.gradleProperty("kotlin-plugin.version").get()
        kotlin("jvm") version kotlinPluginVersion
        kotlin("plugin.spring") version kotlinPluginVersion
        id("com.avast.gradle.docker-compose") version (extra["docker-compose-plugin.version"] as String)
        id("com.bmuschko.docker-spring-boot-application") version (extra["bmuschko-docker-plugin.version"] as String)
        id("io.github.gradle-nexus.publish-plugin") version "2.0.0" apply false
        id("org.octopusden.octopus.oc-template") version (extra["octopus-oc-template.version"] as String)
        id("io.gitlab.arturbosch.detekt") version (extra["detekt.version"] as String)
        id("org.jlleitschuh.gradle.ktlint") version (extra["ktlint-gradle.version"] as String)
        id("org.jetbrains.kotlinx.kover") version (extra["kover.version"] as String)
        id("org.owasp.dependencycheck") version (extra["owasp-dependency-check.version"] as String)
        id("org.octopusden.octopus-quality") version (extra["octopus-quality.version"] as String)
        id("org.sonarqube") version (extra["sonarqube.version"] as String)
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "octopus-teamcity-automation-service"

include(":common")
include(":client")
include(":server")
findProject(":server")?.name = "teamcity-automation-service"
include(":ft")
