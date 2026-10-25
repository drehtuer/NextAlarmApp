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

## Running on the Pixel 10a

The container has no emulator (no hardware acceleration under WSL/Docker) and no USB access. Most code is tested
on the JVM anyway (see [architecture.md](architecture.md#testing)). For instrumented tests and manual testing, use
wireless debugging:

1. On the phone: **Settings → System → Developer options → Wireless debugging**, then **Pair device with pairing
   code**.
2. In the container: `adb pair <ip>:<pairing-port>` and enter the code.
3. Then `adb connect <ip>:<port>` with the port shown on the Wireless debugging screen.

The phone and the development machine must be in the same network.
