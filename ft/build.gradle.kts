import com.avast.gradle.dockercompose.ComposeExtension

plugins {
    id("com.avast.gradle.docker-compose")
}

val ftExcludedFromBuild = gradle.startParameter.excludedTaskNames.any { excludedTask ->
    excludedTask == "test" ||
        excludedTask.endsWith(":test") ||
        excludedTask == "ft" ||
        excludedTask.endsWith(":ft")
}

fun String.getExt() = project.ext[this] as String

sourceSets {
    create("ft") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val ftImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

configurations["ftRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

val serviceLogsDir = layout.buildDirectory.dir("service-logs")

kover {
    currentProject {
        instrumentation {
            // ft needs Docker and is run on its own; without this, koverVerify (and so build and
            // qualityCoverage) would start the whole compose stack.
            disabledForTestTasks.add("ft")
        }
    }
}

configure<ComposeExtension> {
    useComposeFiles.add(
        layout.projectDirectory
            .file("docker/docker-compose.yml")
            .asFile.path,
    )
    waitForTcpPorts.set(true)
    // The standalone docker-compose binary, as plugin 0.16 used; 0.17 defaults to `docker compose`.
    useDockerComposeV2.set(false)
    captureContainersOutputToFiles.set(layout.buildDirectory.dir("docker-logs"))
    environment.putAll(
        mapOf(
            "TEAMCITY_AUTOMATION_SERVICE_VERSION" to version,
            "OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION" to providers.gradleProperty("octopus-components-registry.version").get(),
            "TEAMCITY_2022_IMAGE_TAG" to providers.gradleProperty("teamcity-2022.image-tag").get(),
            "TEAMCITY_2026_IMAGE_TAG" to providers.gradleProperty("teamcity-2026.image-tag").get(),
            "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
            "OCTOPUS_GITHUB_DOCKER_REGISTRY" to "octopusGithubDockerRegistry".getExt(),
        ),
    )
}

val prepareTeamcity2022Data = tasks.register<Sync>("prepareTeamcity2022Data") {
    from(zipTree(layout.projectDirectory.file("docker/data.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2022"))
}

val prepareTeamcity2026Data = tasks.register<Sync>("prepareTeamcity2026Data") {
    from(zipTree(layout.projectDirectory.file("docker/dataV26.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2026"))
}

// A fresh log per run, so the credential check reads only what this run wrote.
val prepareServiceLogs = tasks.register<Delete>("prepareServiceLogs") {
    delete(serviceLogsDir)
    doLast { serviceLogsDir.get().asFile.mkdirs() }
}

tasks.named("composeUp") {
    dependsOn(":teamcity-automation-service:dockerBuildImage")
    dependsOn(prepareTeamcity2022Data, prepareTeamcity2026Data, prepareServiceLogs)
}

val ft by tasks.creating(Test::class) {
    group = "verification"
    description = "Runs the functional tests"

    testClassesDirs = sourceSets["ft"].output.classesDirs
    classpath = sourceSets["ft"].runtimeClasspath

    when ("testPlatform".getExt()) {
        "docker" -> {
            // Hosts as the tests reach them, and as the service reaches them inside the compose network.
            systemProperties["test.teamcity-automation-service-host"] = "localhost:8080"
            systemProperties["test.teamcity-2022-host"] = "localhost:8111"
            systemProperties["test.teamcity-2026-host"] = "localhost:8112"
            systemProperties["test.teamcity-2022-host-for-service"] = "teamcity-2022:8111"
            systemProperties["test.teamcity-2026-host-for-service"] = "teamcity-2026:8111"
            systemProperties["test.components-registry-host-for-service"] = "components-registry-service:4567"
            systemProperties["test.service-log-file"] =
                serviceLogsDir
                    .get()
                    .file("teamcity-automation-service.log")
                    .asFile.absolutePath
            finalizedBy("composeLogs")
        }
        else -> doFirst {
            throw GradleException("ft runs on test.platform=docker only")
        }
    }
}

if ("testPlatform".getExt() == "docker") {
    configure<ComposeExtension> {
        isRequiredBy(ft)
    }
}

val ftInfrastructureTasks = setOf(
    "ft",
    "prepareTeamcity2022Data",
    "prepareTeamcity2026Data",
    "prepareServiceLogs",
)

if (ftExcludedFromBuild) {
    tasks.configureEach {
        if (name in ftInfrastructureTasks || name.startsWith("compose")) {
            enabled = false
        }
    }
}

idea.module {
    // The resolvable view of ftImplementation: a declare-only configuration cannot be resolved.
    scopes["PROVIDED"]?.get("plus")?.add(configurations["ftCompileClasspath"])
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${properties["spring-boot.version"]}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${properties["spring-cloud.version"]}")
    }
}

dependencies {
    ftImplementation(project(":client"))
    ftImplementation(project(":common"))
    ftImplementation("org.junit.jupiter:junit-jupiter-engine")
    ftImplementation("org.junit.jupiter:junit-jupiter-params")
    ftImplementation("com.fasterxml.jackson.core:jackson-databind")
    // Prepares the parent project and reads back what the service created.
    ftImplementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    "ftRuntimeOnly"("ch.qos.logback:logback-classic")
    // Gradle no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    "ftRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
