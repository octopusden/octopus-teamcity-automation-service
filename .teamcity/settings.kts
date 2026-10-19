import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.XmlReport
import jetbrains.buildServer.configs.kotlin.buildFeatures.xmlReport
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.script

version = "2025.03"

project {
    description = "https://github.com/octopusden/octopus-teamcity-automation-service"

    params {
        param("OCTOPUS_MODULE_NAME", "octopus-teamcity-automation-service")
        param("OKD_IMAGE_NAME", "teamcity-automation-service")
        param("env.JAVA_HOME", "%env.JDK_21_0_x64%")
        // octopus-teamcity-automation-core is resolved from GitHub Packages only, which authenticates
        // every read. OCTOPUS_SHARED_PACKAGES_READ_TOKEN is a password parameter (a classic PAT with
        // read:packages only) kept on the TeamCity server, not in this open-source repository.
        param("env.SHARED_PACKAGES_READ_USERNAME", "%OCTOPUS_GITHUB_USERNAME%")
        param("env.SHARED_PACKAGES_READ_TOKEN", "%OCTOPUS_SHARED_PACKAGES_READ_TOKEN%")
    }

    buildType(Build)
    buildType(WL_Validation_id)

    buildTypesOrder = arrayListOf(Build, WL_Validation_id)
}

object Build : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    // Kept from the UI-created configuration, so its build history and number counter carry over.
    id("Build")
    name = "[1.0] Build & FT [AUTO]"

    allowExternalStatus = true
    artifactRules = "%ARTIFACT_PATH%"

    params {
        param(
            "ARTIFACT_PATH",
            """
            **/build/reports/** => reports
            **/build/test-results/**/*.xml => test-results
            ft/build/okd/** => okd
            """.trimIndent(),
        )
        // The template's "Gradle Build & UT" step runs this: compile, unit tests and the quality gates.
        param("GRADLE_TASK", "clean build")
        // The functional tests, in an OKD namespace of their own: ft pushes the image under test
        // (dockerPushImage) and creates TeamCity 2022/2026, the Components Registry and the service as pods.
        param("GRADLE_FT_TASK", ":ft:ft")
        param("OKD_PROJECT_NAME", "%OKD_F1_TEST_PROJECT%")
        param(
            "GRADLE_EXTRA_PARAMETERS",
            """
            -Ptest.platform=okd
            -Pokd.active-deadline-seconds=1800
            -Pokd.project=%OKD_PROJECT_NAME%
            -Pokd.cluster-domain=%OKD_APPS_DOMAIN_DEV%
            -Pokd.web-console-url=https://console-openshift-console.%OKD_APPS_DOMAIN_DEV%
            -Pokd.service-account-anyuid=teamcity
            """.trimIndent(),
        )
    }

    steps {
        script {
            name = "Login to the OKD cluster"
            id = "Login_to_the_OKD_cluster"
            scriptContent =
                """
                oc login --token=%OKD_F1_FT_TOKEN% %OKD_SERVER_DEV_URL%
                oc project %OKD_PROJECT_NAME%
                """.trimIndent()
        }
        gradle {
            name = "Functional tests"
            id = "Functional_tests"
            tasks = "%GRADLE_FT_TASK%"
            workingDir = "%WORK_DIR%"
            gradleParams =
                """
                --info
                %GRADLE_STANDARD_PARAMETERS%
                %GRADLE_EXTRA_PARAMETERS%
                """.trimIndent()
            enableStacktrace = true
            jdkHome = "%env.JAVA_HOME%"
            jvmArgs = "%JDK_CMDLINE_PARAMETERS%"
        }
        script {
            name = "Logout from the OKD cluster"
            id = "Logout_from_the_OKD_cluster"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent =
                """
                oc logout || true
                rm -rf ~/.kube/
                """.trimIndent()
        }
        // RUNNER_1720 (Calculate build parameters) and RUNNER_1768 (Gradle Build & UT) come from the template.
        stepsOrder =
            arrayListOf(
                "RUNNER_1720",
                "RUNNER_1768",
                "Login_to_the_OKD_cluster",
                "Functional_tests",
                "Logout_from_the_OKD_cluster",
            )
    }

    failureConditions {
        executionTimeoutMin = 120
    }

    features {
        xmlReport {
            id = "JUnit_reports"
            reportType = XmlReport.XmlReportType.JUNIT
            rules =
                """
                +:**/build/test-results/test/*.xml
                +:ft/build/test-results/ft/*.xml
                """.trimIndent()
        }
    }

    requirements {
        // oc and docker
        doesNotContain("env.OS_TYPE", "WIN", "RQ_LINUX")
    }
})

object WL_Validation_id : BuildType({
    templates(AbsoluteId("OctopusWlValidator"))
    name = "WL Validation"

    params {
        param("OCTOPUS_MODULE_NAME", "octopus-teamcity-automation-service")
    }
})
