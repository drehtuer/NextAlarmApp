plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.sonarqube)
}

sonar {
    properties {
        property("sonar.projectKey", "drehtuer_NextAlarmApp")
        property("sonar.organization", "drehtuer")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.androidVariant", "debug")
        // Generated code, such as Hilt's, is not ours to fix and would distort the coverage.
        property("sonar.exclusions", "**/build/**")
        // The reports are written by the task `coverageReports`, which has to run before `sonar`.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            listOf(
                "domain/build/reports/jacoco/test/jacocoTestReport.xml",
                "data/build/reports/coverage/test/debug/report.xml",
                "app/build/reports/coverage/test/debug/report.xml",
            ).joinToString(",") { rootDir.resolve(it).path },
        )
    }
}

// Runs all unit tests and writes the line and branch coverage of every module as JaCoCo XML.
tasks.register("coverageReports") {
    group = "verification"
    description = "Runs the unit tests of all modules and writes their coverage reports."
    dependsOn(
        ":domain:jacocoTestReport",
        ":data:createDebugUnitTestCoverageReport",
        ":app:createDebugUnitTestCoverageReport",
    )
}
