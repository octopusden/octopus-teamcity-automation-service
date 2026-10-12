import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.InetAddress
import java.util.zip.CRC32

plugins {
    java
    idea
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlin.jvm") apply false
    id("io.github.gradle-nexus.publish-plugin")
    id("org.jetbrains.kotlinx.kover")
    id("org.owasp.dependencycheck")
    id("io.gitlab.arturbosch.detekt") apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
    id("org.octopusden.octopus-quality")
    id("org.sonarqube")
    signing
}

val defaultVersion = "${
    with(CRC32()) {
        update(InetAddress.getLocalHost().hostName.toByteArray())
        value
    }
}-SNAPSHOT"

fun requiredIntProperty(propertyName: String): Int {
    val value = findProperty(propertyName)?.toString()?.trim()
        ?: throw IllegalStateException("Missing Gradle property '$propertyName' required for Kover verification")
    return value.toIntOrNull()
        ?: throw IllegalStateException("Gradle property '$propertyName' must be an integer, but was '$value'")
}

val testsExcludedFromBuild = gradle.startParameter.excludedTaskNames.any { excludedTask ->
    excludedTask == "test" || excludedTask.endsWith(":test")
}

allprojects {
    group = "org.octopusden.octopus.teamcity-automation-service"
    if (version == "unspecified") {
        version = defaultVersion
    }
}

repositories {
    mavenCentral()
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("MAVEN_USERNAME"))
            password.set(System.getenv("MAVEN_PASSWORD"))
        }
    }
}

dependencies {
    val coverageProjects = listOf(
        ":client",
        ":common",
        ":teamcity-automation-service",
    )
    coverageProjects.forEach { add("kover", project(it)) }
}

kover {
    reports {
        verify {
            rule("Line coverage") {
                bound {
                    minValue.set(requiredIntProperty("coverage.line.min"))
                    coverageUnits = CoverageUnit.LINE
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("Branch coverage") {
                bound {
                    minValue.set(requiredIntProperty("coverage.branch.min"))
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

octopusQuality {
    // Regression guard on what this repository publishes. The server is a deployable shipped as a
    // docker image, so only the two libraries other services consume are listed.
    publication {
        enforceCentralPublications.set(true)
        centralPublications.set(
            setOf(
                ":client|maven|org.octopusden.octopus.teamcity-automation-service:client|" +
                    "[jar, jar:javadoc, jar:sources]",
                ":common|maven|org.octopusden.octopus.teamcity-automation-service:common|" +
                    "[jar, jar:javadoc, jar:sources]",
            ),
        )
    }
    kotlin {
        failOnViolation.set(true)
    }
    coverage {
        // Enabled so the convention plugin wires qualityCoverage → kover tasks.
        // Kover itself is configured manually above (custom thresholds + branch coverage);
        // plugin does not override consumer kover config, only aggregates tasks.
        enabled.set(true)
    }
    excludeProjects("ft")
}

dependencyCheck {
    failBuildOnCVSS = 11.0F
    formats = listOf("HTML", "JSON", "SARIF")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
}

tasks.register("securityReport") {
    group = "verification"
    description = "Runs security checks in report-only mode."
    dependsOn(
        if (tasks.names.contains("dependencyCheckAggregate")) {
            "dependencyCheckAggregate"
        } else {
            "dependencyCheckAnalyze"
        }
    )
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "idea")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // detekt + ktlint are configured by octopus-quality convention plugin
    apply(plugin = "signing")

    repositories {
        mavenCentral()
    }

    idea.module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }

    java {
        withJavadocJar()
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            suppressWarnings.set(true)
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // The Kotlin Gradle plugin (kotlin-plugin.version) is newer than the Kotlin runtime these modules
    // ship with and run on (kotlin.version). Hold the compiler to the runtime's level so the bytecode
    // and metadata stay what consumers of the published modules can read, and no call can reach a
    // stdlib API newer than the stdlib on the classpath.
    extensions.configure<KotlinJvmProjectExtension> {
        // The version the plugin gives kotlin-stdlib / kotlin-reflect and its own constraints on them;
        // it defaults to the plugin's version, which would leak into the published POMs and modules.
        coreLibrariesVersion = providers.gradleProperty("kotlin.version").get()
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_1_9)
            apiVersion.set(KotlinVersion.KOTLIN_1_9)
        }
    }

    // The imported Spring BOMs pin every org.jetbrains.kotlin artifact to the runtime version, and
    // dependency-management applies them to every configuration - including the Kotlin plugin's own
    // compiler, build-tools and compiler-plugin classpaths, where a 1.9 compiler under a 2.x plugin
    // fails to load. Give the Kotlin artifacts there back the versions the plugin asks for;
    // registered after dependency-management's rule, this one runs last. kotlinScriptDef* resolve
    // from the project's classpath and stay managed.
    configurations
        .matching { it.name.startsWith("kotlin") && !it.name.startsWith("kotlinScriptDef") }
        .configureEach {
            resolutionStrategy.eachDependency {
                val version = requested.version
                if (requested.group.startsWith("org.jetbrains.kotlin") && !version.isNullOrEmpty()) {
                    useVersion(version)
                }
            }
        }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        }
    }

    if (testsExcludedFromBuild) {
        tasks.configureEach {
            if (name.startsWith("kover")) {
                enabled = false
            }
        }
    }

    ext {
        System.getenv().let {
            set("signingRequired", it.containsKey("ORG_GRADLE_PROJECT_signingKey") && it.containsKey("ORG_GRADLE_PROJECT_signingPassword"))
            set("testPlatform", it.getOrDefault("TEST_PLATFORM", properties["test.platform"]))
            set("dockerRegistry", it.getOrDefault("DOCKER_REGISTRY", properties["docker.registry"]))
            set("octopusGithubDockerRegistry", it.getOrDefault("OCTOPUS_GITHUB_DOCKER_REGISTRY", project.properties["octopus.github.docker.registry"]))
            set("okdActiveDeadlineSeconds", it.getOrDefault("OKD_ACTIVE_DEADLINE_SECONDS", properties["okd.active-deadline-seconds"]))
            set("okdProject", it.getOrDefault("OKD_PROJECT", properties["okd.project"]))
            set("okdClusterDomain", it.getOrDefault("OKD_CLUSTER_DOMAIN", properties["okd.cluster-domain"]))
            set("okdWebConsoleUrl", (it.getOrDefault("OKD_WEB_CONSOLE_URL", properties["okd.web-console-url"]) as String).trimEnd('/'))
        }
    }
    val supportedTestPlatforms = listOf("docker", "okd")
    if (project.ext["testPlatform"] !in supportedTestPlatforms) {
        throw IllegalArgumentException("Test platform must be set to one of the following $supportedTestPlatforms. Start gradle build with -Ptest.platform=... or set env variable TEST_PLATFORM")
    }
    val mandatoryProperties = mutableListOf("dockerRegistry", "octopusGithubDockerRegistry")
    if (project.ext["testPlatform"] == "okd") {
        mandatoryProperties.add("okdActiveDeadlineSeconds")
        mandatoryProperties.add("okdProject")
        mandatoryProperties.add("okdClusterDomain")
    }
    val undefinedProperties = mandatoryProperties.filter { (project.ext[it] as String).isBlank() }
    if (undefinedProperties.isNotEmpty()) {
        throw IllegalArgumentException(
            "Start gradle build with" +
                    (if (undefinedProperties.contains("dockerRegistry")) " -Pdocker.registry=..." else "") +
                    (if (undefinedProperties.contains("octopusGithubDockerRegistry")) " -Poctopus.github.docker.registry=..." else "") +
                    (if (undefinedProperties.contains("okdActiveDeadlineSeconds")) " -Pokd.active-deadline-seconds=..." else "") +
                    (if (undefinedProperties.contains("okdProject")) " -Pokd.project=..." else "") +
                    (if (undefinedProperties.contains("okdClusterDomain")) " -Pokd.cluster-domain=..." else "") +
                    " or set env variable(s):" +
                    (if (undefinedProperties.contains("dockerRegistry")) " DOCKER_REGISTRY" else "") +
                    (if (undefinedProperties.contains("octopusGithubDockerRegistry")) " OCTOPUS_GITHUB_DOCKER_REGISTRY" else "") +
                    (if (undefinedProperties.contains("okdActiveDeadlineSeconds")) " OKD_ACTIVE_DEADLINE_SECONDS" else "") +
                    (if (undefinedProperties.contains("okdProject")) " OKD_PROJECT" else "") +
                    (if (undefinedProperties.contains("okdClusterDomain")) " OKD_CLUSTER_DOMAIN" else "")
        )
    }
}
