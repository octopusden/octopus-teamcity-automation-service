import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.XmlReport
import jetbrains.buildServer.configs.kotlin.buildFeatures.xmlReport
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger

version = "2025.03"

project {
    description = "https://github.com/octopusden/octopus-teamcity-automation-service"

    params {
        param("OCTOPUS_MODULE_NAME", "octopus-teamcity-automation-service")
        // TEMPORARY, remove before merging: points the shared VCS root, and with it the versioned
        // settings sync, at the branch that holds this DSL. Until the merge, main has no .teamcity/
        // and a sync from it would read the project as deleted.
        param("OCTOPUS_DEFAULT_BRANCH", "build-chain-service")
        param("OKD_IMAGE_NAME", "teamcity-automation-service")
        param("env.JAVA_HOME", "%env.JDK_21_0_x64%")
        // octopus-teamcity-automation-core is resolved from GitHub Packages only, which authenticates
        // every read. The token (a classic PAT with read:packages only) comes from Vault through the
        // inherited HashiCorp Vault connection, next to the other octopusden GitHub tokens.
        text("env.SHARED_PACKAGES_READ_USERNAME", "%OCTOPUS_GITHUB_USERNAME%", readOnly = true, allowEmpty = false)
        password("env.SHARED_PACKAGES_READ_TOKEN", "%vault:octopus/data/GitHub!/OCTOPUS_SHARED_PACKAGES_READONLY_TOKEN%", readOnly = true)
    }

    buildType(CompileUt)
    buildType(Ft)
    buildType(Aggregator)
    buildType(WL_Validation_id)

    buildTypesOrder = arrayListOf(CompileUt, Ft, Aggregator, WL_Validation_id)
}

object CompileUt : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"), AbsoluteId("RDDepartment_PostGithubStatus"))
    // Kept from the UI-created configuration, so its build history and number counter carry over.
    id("Build")
    name = "[1.0] Compile & UT [AUTO]"

    allowExternalStatus = true
    artifactRules = "%ARTIFACT_PATH%"

    params {
        param(
            "ARTIFACT_PATH",
            """
            **/build/reports/** => reports
            **/build/test-results/**/*.xml => test-results
            """.trimIndent(),
        )
        // Compile, unit tests and the quality gates, then push the image [2.0] tests and the deployments take.
        param("GRADLE_TASK", "clean build dockerPushImage")
    }

    features {
        xmlReport {
            id = "JUnit_reports"
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:**/build/test-results/test/*.xml"
        }
    }

    requirements {
        // docker
        doesNotContain("env.OS_TYPE", "WIN", "RQ_LINUX")
    }
})

object Ft : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("20FtAuto")
    name = "[2.0] FT [AUTO]"

    // The same version as [1.0], so the functional tests run the very image [1.0] pushed.
    buildNumberPattern = "${CompileUt.depParamRefs.buildNumber}"
    artifactRules = "%ARTIFACT_PATH%"

    params {
        param(
            "ARTIFACT_PATH",
            """
            ft/build/reports/** => reports
            ft/build/test-results/**/*.xml => test-results
            ft/build/okd/** => okd
            """.trimIndent(),
        )
        param("GRADLE_TASK", "ft")
        param("PLATFORM", "okd")
        param("OKD_PROJECT_NAME", "%OKD_F1_TEST_PROJECT%")
        // ft creates TeamCity 2022/2026, the Components Registry and the service as pods of their own
        // in the namespace. The image is [1.0]'s, so ft does not push one again.
        param(
            "GRADLE_EXTRA_PARAMETERS",
            """
            -Ptest.platform=%PLATFORM%
            -Pokd.active-deadline-seconds=1800
            -Pokd.project=%OKD_PROJECT_NAME%
            -Pokd.cluster-domain=%OKD_APPS_DOMAIN_DEV%
            -Pokd.web-console-url=https://console-openshift-console.%OKD_APPS_DOMAIN_DEV%
            -Pokd.service-account-anyuid=teamcity
            -x :teamcity-automation-service:dockerPushImage
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
        // RUNNER_1720 (Calculate build parameters) and RUNNER_1768 (Gradle, running %GRADLE_TASK%) come from the template.
        stepsOrder =
            arrayListOf(
                "RUNNER_1720",
                "Login_to_the_OKD_cluster",
                "RUNNER_1768",
                "Logout_from_the_OKD_cluster",
            )
    }

    triggers {
        finishBuildTrigger {
            buildType = "${CompileUt.id}"
            successfulOnly = true
            branchFilter = "+:*"
        }
    }

    dependencies {
        snapshot(CompileUt) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.FAIL_TO_START
            synchronizeRevisions = true
        }
    }

    failureConditions {
        executionTimeoutMin = 120
    }

    features {
        xmlReport {
            id = "JUnit_reports"
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:ft/build/test-results/ft/*.xml"
        }
    }

    requirements {
        // oc
        doesNotContain("env.OS_TYPE", "WIN", "RQ_LINUX")
    }
})

object Aggregator : BuildType({
    // Only the template's two steps: the build/integration-a commit status for the whole chain.
    templates(AbsoluteId("RDDepartment_PostGithubStatus"))
    id("2xAggregator")
    name = "[2.X] Aggregator"

    buildNumberPattern = "${CompileUt.depParamRefs.buildNumber}"

    triggers {
        // On every FT result, not only success: [1.0] has already posted success for the same
        // commit, so a failed FT has to be reported from here.
        finishBuildTrigger {
            buildType = "${Ft.id}"
            successfulOnly = false
            branchFilter = "+:*"
        }
    }

    dependencies {
        snapshot(Ft) {
            // Runs on a failed FT too and fails itself, so its "Failed" step posts the status.
            onDependencyFailure = FailureAction.ADD_PROBLEM
            onDependencyCancel = FailureAction.FAIL_TO_START
            synchronizeRevisions = true
        }
    }
})

object WL_Validation_id : BuildType({
    templates(AbsoluteId("OctopusWlValidator"))
    name = "WL Validation"

    params {
        param("OCTOPUS_MODULE_NAME", "octopus-teamcity-automation-service")
    }
})
