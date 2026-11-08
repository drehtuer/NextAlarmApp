pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NextAlarm"

include(":app", ":data", ":domain")

// Security floors for libraries that the build tools pull in, mostly through the Android Gradle plugin, lint, JaCoCo,
// KSP and Hilt. None of them ships in the APK. Each one is raised to the first version without the advisories that
// Dependabot reports, for every configuration of every project and for the build scripts' own classpath. A floor can
// go once the tools that bring the library in require a fixed version themselves.
// See docs/development.md#dependency-updates-and-security.
val securityFloors = mapOf(
    "com.google.guava:guava" to "33.7.2",
    "org.bouncycastle:bcprov-jdk18on" to "1.85",
    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
    "org.bouncycastle:bcutil-jdk18on" to "1.85",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.jdom:jdom2" to "2.0.6.1",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
)

/** The numeric part of a version, "33.4.0-jre" as 33, 4, 0. */
fun versionNumbers(version: String): List<Int> = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

fun isBelow(version: String, floor: String): Boolean {
    val have = versionNumbers(version)
    val need = versionNumbers(floor)
    for (index in 0 until maxOf(have.size, need.size)) {
        val difference = have.getOrElse(index) { 0 } - need.getOrElse(index) { 0 }
        if (difference != 0) return difference < 0
    }
    return false
}

/** Guava comes as "-jre" and "-android"; the raised version keeps the flavour that was asked for. */
fun raisedVersion(module: String, requested: String, floor: String): String = when {
    module != "com.google.guava:guava" -> floor
    requested.endsWith("-android") -> "$floor-android"
    else -> "$floor-jre"
}

fun Configuration.applySecurityFloors() {
    resolutionStrategy.eachDependency {
        val module = "${requested.group}:${requested.name}"
        val floor = securityFloors[module] ?: return@eachDependency
        val version = requested.version
        if (!version.isNullOrEmpty() && isBelow(version, floor)) {
            useVersion(raisedVersion(module, version, floor))
            because("security floor, see settings.gradle.kts")
        }
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { applySecurityFloors() }
    configurations.configureEach { applySecurityFloors() }
}
