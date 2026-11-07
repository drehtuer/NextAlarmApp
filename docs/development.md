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

### Updating dependencies

Gradle checks every dependency against the checksums in `gradle/verification-metadata.xml`, and the build fails on
one it does not know. After adding or updating a dependency, including a version in `gradle/libs.versions.toml`,
regenerate the file and review the change. Use an empty Gradle cache, because artifacts that are already cached can
be missed, and the build then fails on a clean machine such as CI:

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 --no-configuration-cache \
    clean coverageReports :app:assembleDebug :app:assembleDebugAndroidTest sonarResolver
```

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

Findings that are wrong for this project are ignored in the `sonar` block of `build.gradle.kts`, each with its reason,
rather than in SonarCloud, so that the decision is reviewed with the code. The only one is `xml:S5332` (clear-text
traffic) in the manifest: it is allowed on purpose, for podcasts (see [Feeds](architecture.md#feeds)).

## Dependency updates and security

| What | Where | What it does |
| --- | --- | --- |
| Dependabot updates | `.github/dependabot.yml` | Weekly pull requests for Gradle dependencies and GitHub Actions, monthly ones for the dev container's base image and features. A release is only proposed after 14 days. Minor and patch updates of Gradle dependencies come in one pull request. |
| Dependabot alerts and security updates | Repository settings | Alerts for dependencies with a known vulnerability, and pull requests that fix them. |
| Dependency submission | `.github/workflows/dependency-submission.yml` | On every push to `main`, sends the resolved Gradle dependencies to GitHub's dependency graph, which cannot read them from the build files. The alerts for Gradle dependencies depend on it. |
| CodeQL | `.github/workflows/codeql.yml` | Code scanning with the security and quality queries, for the Kotlin code and the workflows, on pull requests, on `main` and weekly. Findings are under **Security → Code scanning**. |
| OpenSSF Scorecard | `.github/workflows/scorecard.yml` | Checks the repository's security practices on `main` and weekly, and publishes the score shown in the README. |
| Secret scanning | Repository settings | Finds committed secrets and blocks pushes that contain them. |
| Private vulnerability reporting | Repository settings | How [SECURITY.md](SECURITY.md) asks for vulnerabilities to be reported. |

**Dependabot's Gradle pull requests fail until the checksums are added.** Dependabot does not update
`gradle/verification-metadata.xml`, so the build refuses the new artifacts. Check out the pull request's branch,
regenerate the file as described in [Updating dependencies](#updating-dependencies), review the change and push it to
the branch. Dependabot keeps rebasing the branch only as long as nobody else has pushed to it.

The Android SDK versions in `.devcontainer/Dockerfile` and in the workflows are not updated by Dependabot; see
[Updating SDK versions](#updating-sdk-versions). Keep them in line in `build.yml`, `codeql.yml` and
`dependency-submission.yml`.

CodeQL runs as a workflow (advanced setup), because Kotlin is only analysed while it is compiled. The default setup
under **Settings → Code security** must stay off, as the two cannot run side by side.

## Documentation site

[`.github/workflows/pages.yml`](../.github/workflows/pages.yml) publishes `README.md` and `docs/` as the GitHub Pages site
<https://drehtuer.github.io/NextAlarmApp/> whenever a release is published. It can also be started by hand under
**Actions → Documentation → Run workflow**, for a branch or tag.

1. `.github/scripts/stage-pages.sh` copies the pages into one directory: `README.md` becomes the start page
   `index.md` and the files of `docs/` sit next to it. Links that leave `docs/` (to `LICENSE` or a workflow, say) are
   rewritten to the file on GitHub at the release's tag, so the site matches the release.
2. `actions/jekyll-build-pages` builds it with the same Jekyll and plugins as GitHub Pages. Links between the Markdown
   files become links between the pages. The site's configuration, theme and includes are in `.github/pages/`, so that
   `docs/` holds only Markdown.
3. GitHub's Jekyll does not render Mermaid, so `.github/pages/_includes/head-custom.html` draws the diagrams in the
   browser with Mermaid from jsDelivr, at a fixed version.

This needs two settings outside the repository:

1. Under **Settings → Pages**, the source must be **GitHub Actions**.
2. The `github-pages` environment only lets `main` deploy by default, but a release runs on its tag. Under
   **Settings → Environments → github-pages → Deployment branches and tags**, add a tag rule such as `v*`.

To preview the site, stage it and build it with the image the action uses (a token is needed for the repository
metadata):

```bash
.github/scripts/stage-pages.sh _pages https://github.com/drehtuer/NextAlarmApp main
docker run --rm -v "$PWD":/ws -w /ws -e GITHUB_WORKSPACE=/ws -e GITHUB_REPOSITORY=drehtuer/NextAlarmApp \
    -e INPUT_SOURCE=_pages -e INPUT_DESTINATION=_site -e INPUT_FUTURE=false -e INPUT_VERBOSE=false \
    -e INPUT_BUILD_REVISION=local -e INPUT_TOKEN="$(gh auth token)" \
    --entrypoint /entrypoint.sh ghcr.io/actions/jekyll-build-pages:v1.0.13
```

`_pages/` and `_site/` are ignored by Git. Keep the image version in line with `actions/jekyll-build-pages` in the
workflow.

## Running on the Pixel 10a

The container has no emulator (no hardware acceleration under WSL/Docker) and no USB access. Most code is tested
on the JVM anyway (see [architecture.md](architecture.md#testing)). For instrumented tests and manual testing, use
wireless debugging:

1. On the phone: **Settings → System → Developer options → Wireless debugging**, then **Pair device with pairing
   code**.
2. In the container: `adb pair <ip>:<pairing-port>` and enter the code.
3. Then `adb connect <ip>:<port>` with the port shown on the Wireless debugging screen. This port changes whenever
   wireless debugging restarts.

The phone and the development machine must be in the same network. `adb` on the development machine works as well, if
it is installed there.

To install and start the debug build:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n de.drehtuer.nextalarm/.MainActivity
```

### Silent device tests

`app/src/androidTest` holds tests that only a real phone can answer. They fire real alarms through the real receivers,
but every alarm plays at volume 0, without vibration or speech, so they can run at any time. They don't change the phone's
alarm volume or ask for the audio focus, which would pause what plays. Their screen can still light up. They use their own alarms in the app's database and delete them.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w de.drehtuer.nextalarm.test/androidx.test.runner.AndroidJUnitRunner
```

The one exception to the silence is a database that fails while an alarm fires: the app then cannot know that the alarm is
silent, and rings the bundled sound at full level. See [When the database fails](architecture.md#when-the-database-fails).

`am instrument` force-stops the app first. That clears its registered alarms until the app next starts, when the
recovery sweep registers them again, so open the app afterwards. Add `-e holdSeconds 15` to keep the alarm screen up for
a screenshot.

These fire with `sendBroadcast`. Two more tests go through the real `AlarmManager`, and are skipped unless an argument asks
for them. The first leaves a silent alarm behind, and the process ends with the test, so the alarm fires later in a cold
process. The second ends it:

```bash
# Rings silently in 5 minutes. The title of the alarm starts with "Device test".
adb shell am instrument -w -e class de.drehtuer.nextalarm.SilentDeviceTest#silentAlarmThroughAlarmManager \
    -e silentAlarmIn 300 de.drehtuer.nextalarm.test/androidx.test.runner.AndroidJUnitRunner

# Dismisses every device test alarm, and deletes it.
adb shell am instrument -w -e class de.drehtuer.nextalarm.SilentDeviceTest#endSilentAlarms \
    -e endSilentAlarms true de.drehtuer.nextalarm.test/androidx.test.runner.AndroidJUnitRunner
```

The alarm has volume 0, no vibration, no speech and no news, and the test fails rather than create one with anything else.
It stays silent through its ring timeout and snooze. Android may clear the registration when the instrumentation ends and
stops the app: if the alarm does not fire, that is the first thing to check (`adb shell dumpsys alarm`), rather than a fault
in the alarm path. Opening the app registers it again, through the recovery sweep. The second command starts a process of
its own, so it finds no sound to stop unless the sweep rang the alarm in it, and it goes by the stored state.
