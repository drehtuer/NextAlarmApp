# Development

## Dev container

The repository contains a [dev container](https://containers.dev/) in `.devcontainer/` with everything needed to
build the app and run the unit tests:

| Component                | Version                                                   |
|--------------------------|-----------------------------------------------------------|
| Base image               | `mcr.microsoft.com/devcontainers/base:ubuntu-24.04`       |
| JDK                      | OpenJDK 21 (headless)                                     |
| Android command-line tools | 23.0 (`sdkmanager`)                                     |
| Android SDK platform     | `platforms;android-37.0` (Android 17)                     |
| Android build tools      | 37.0.0                                                    |
| Android platform tools   | latest at image build time (`adb`)                        |
| GitHub CLI               | latest, via dev container feature                         |

The SDK licenses are accepted during the image build, and `ANDROID_HOME` (`/opt/android-sdk`) is writable by the
container user, so the Android Gradle plugin can download further SDK packages itself. Gradle is not installed in
the image: the project uses the Gradle wrapper. The Gradle cache (`~/.gradle`) is a named Docker volume
(`nextalarm-gradle-cache`), so dependencies survive a rebuild of the container.

VS Code extensions installed in the container: Claude Code, Kotlin, Gradle, Mermaid preview for Markdown and
EditorConfig.

To use it, open the repository in VS Code and choose **Dev Containers: Reopen in Container**.

### Updating SDK versions

The versions are build arguments at the top of `.devcontainer/Dockerfile`. The available packages, download
names and SHA-1 checksums are listed in Google's repository index
`https://dl.google.com/android/repository/repository2-3.xml`. After changing them, rebuild the container.

## Building and testing

| Command                            | What it does                                                        |
|------------------------------------|---------------------------------------------------------------------|
| `./gradlew :app:assembleDebug`     | Builds the debug APK.                                               |
| `./gradlew coverageReports`        | Runs the unit tests of all modules and writes their coverage reports. |

The coverage reports are JaCoCo XML and HTML files, written to `domain/build/reports/jacoco/test/` and to
`build/reports/coverage/test/debug/` in `app` and `data`. They contain line and branch coverage.

Robolectric downloads its Android runtime from Maven Central into `~/.m2/repository` on the first run. Maven Central
sometimes answers this download with HTTP 429; if a test fails with `Failed to fetch maven artifact`, run it again later.

## Continuous integration and SonarCloud

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs on every pull request and on every push to
`main`. It builds the app, runs the unit tests with coverage and sends the analysis to
[SonarCloud](https://sonarcloud.io/project/overview?id=drehtuer_NextAlarmApp), which shows the line and branch coverage.

SonarCloud can only import coverage from a CI analysis, so this needs two settings outside the repository:

1. In SonarCloud, turn off **Automatic Analysis** under **Administration → Analysis Method**. It cannot run alongside
   a CI analysis.
2. In SonarCloud, generate a token under **My Account → Security**, and store it in GitHub as the repository secret
   `SONAR_TOKEN` under **Settings → Secrets and variables → Actions**.

The SDK versions in the workflow must match those in `.devcontainer/Dockerfile`.

## Running on the Pixel 10a

The container has no emulator (no hardware acceleration under WSL/Docker) and no USB access. Most code is tested
on the JVM anyway (see [architecture.md](architecture.md#testing)). For instrumented tests and manual testing, use
wireless debugging:

1. On the phone: **Settings → System → Developer options → Wireless debugging**, then **Pair device with pairing
   code**.
2. In the container: `adb pair <ip>:<pairing-port>` and enter the code.
3. Then `adb connect <ip>:<port>` with the port shown on the Wireless debugging screen.

The phone and the development machine must be in the same network.
