plugins {
    id("org.springframework.boot")
    id("org.jetbrains.kotlin.plugin.spring")
    id("com.bmuschko.docker-spring-boot-application")
}

// Not published to Maven Central: the server is a deployable, its deliverable is the docker
// image on ghcr, and nothing consumes it as a Maven dependency. The consumed libraries
// (:client, :common) publish from their own build scripts.

fun String.getExt() = project.ext[this] as String

repositories {
    // The build-chain library is published to GitHub Packages only, which authenticates every read.
    // CI gets the credentials from octopus-base (OCTOPUS_SHARED_PACKAGES_READ_TOKEN); a local build needs
    // a personal token with read:packages in the same two variables. Exclusive, so no other repository,
    // Maven Central included, is ever asked for this group.
    exclusiveContent {
        forRepository {
            maven {
                name = "OctopusMavenPackages"
                url = uri("https://maven.pkg.github.com/octopusden/octopus-maven-packages")
                credentials {
                    username = System.getenv("SHARED_PACKAGES_READ_USERNAME")
                    password = System.getenv("SHARED_PACKAGES_READ_TOKEN")
                }
            }
        }
        filter {
            includeGroup("org.octopusden.octopus.automation.teamcity")
        }
    }
}

docker {
    springBootApplication {
        baseImage.set("${"dockerRegistry".getExt()}/eclipse-temurin:21-jdk")
        ports.set(listOf(8080, 8080))
        images.set(setOf("${"octopusGithubDockerRegistry".getExt()}/octopusden/$name:$version"))
    }
}

springBoot {
    buildInfo()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${properties["spring-boot.version"]}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${properties["spring-cloud.version"]}")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(
        "org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation-core:${properties["octopus-teamcity-automation.version"]}",
    )
    implementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    implementation(
        "org.octopusden.octopus.infrastructure:components-registry-service-client:" +
            "${properties["octopus-components-registry-service-client.version"]}",
    )
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.cloud:spring-cloud-starter")
    implementation("org.springframework.cloud:spring-cloud-starter-bootstrap")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${properties["springdoc-openapi.version"]}")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:${properties["mockito-kotlin.version"]}")
    // Gradle no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.all {
    exclude("commons-logging", "commons-logging")
}
