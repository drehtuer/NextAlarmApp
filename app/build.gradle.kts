import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

// Release signing comes from the environment, so that the key never enters the repository. Without it, the release build
// is unsigned. See docs/development.md#releases.
val signingKeystore: Provider<String> = providers.environmentVariable("SIGNING_KEYSTORE_FILE")
val releaseSigned = signingKeystore.isPresent

android {
    namespace = "de.drehtuer.nextalarm"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "de.drehtuer.nextalarm"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigned) {
            create("release") {
                val password = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").get()
                storeFile = file(signingKeystore.get())
                storePassword = password
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orElse("nextalarm").get()
                // A PKCS12 keystore has one password for the store and its keys.
                keyPassword = password
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            if (releaseSigned) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * Copies the APK of a variant to `build/outputs/named-apk/<variant>/` under the name it is shared with:
 * `NextAlarm-<version>.apk` for a release (`-unsigned` added without the key), and
 * `NextAlarm-debug-<version>-<UTC build time>.apk` otherwise. AGP's own output keeps its name.
 */
abstract class NamedApk : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val signed: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val built = checkNotNull(loader.get().load(apkFolder.get())) { "No APK in ${apkFolder.get()}" }
        val apk = File(built.elements.single().outputFile)
        val version = versionName.get()
        val name = if (buildType.get() == "release") {
            "NextAlarm-$version" + (if (signed.get()) "" else "-unsigned") + ".apk"
        } else {
            val time = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            "NextAlarm-${buildType.get()}-$version-$time.apk"
        }
        val directory = outputDirectory.get().asFile
        directory.listFiles()?.forEach { it.delete() }
        apk.copyTo(directory.resolve(name))
        logger.lifecycle("APK: ${directory.resolve(name)}")
    }
}

androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar(Char::uppercase)
        val named = tasks.register<NamedApk>("named${capitalised}Apk") {
            description = "Copies the ${variant.name} APK under the name it is shared with."
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            buildType.set(variant.buildType ?: variant.name)
            versionName.set(variant.outputs.single().versionName.orElse(""))
            signed.set(variant.buildType != "release" || releaseSigned)
            outputDirectory.set(layout.buildDirectory.dir("outputs/named-apk/${variant.name}"))
        }
        tasks.matching { it.name == "assemble$capitalised" }.configureEach { finalizedBy(named) }
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
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.hilt.android)
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.compiler)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.compose.ui.test.junit4)
    // The versions Compose brings in call an InputManager method that no longer exists on API 37.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
