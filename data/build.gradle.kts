import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    jacoco
}

android {
    namespace = "de.drehtuer.nextalarm.data"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 37
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// Robolectric loads classes without a code source; JaCoCo skips those unless told otherwise.
tasks.withType<Test>().configureEach {
    // Robolectric reads FileDescriptor internals through SharedSecrets, which Java 21 blocks by default.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
