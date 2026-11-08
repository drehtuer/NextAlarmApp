# Implementation status

The progress of each step in [TODO.md](TODO.md). Update this file in the same change that starts or finishes a step.

**Last updated:** 2026-11-08

**Summary:** Phases 0 to 8, the silent device tests and the quick settings tile are merged. Several alarm calendars, a settable alarm volume, audio focus, call handling and http only for podcasts are merged in #20. The documentation is published as a GitHub Pages site with every release. The first real alarm rang audibly on the phone on 2026-11-08. The rest of phase 9 needs the owner. Hue is waiting for Q-H5.

| Status        | Meaning                                                       |
|---------------|---------------------------------------------------------------|
| `Not started` | No work has been done.                                        |
| `In progress` | Work has started, or the step is partly merged.               |
| `Blocked`     | Cannot continue; the Notes column says why.                   |
| `Done`        | Merged into `main`, with tests and documentation up to date.  |

## Groundwork

| Item                                      | Status | Notes                                   |
|-------------------------------------------|--------|-----------------------------------------|
| Concept and architecture documents        | `Done` | Later decisions are updated as they come. |
| [Dev container](development.md)           | `Done` | Merged in #4.                           |
| [Documentation site](development.md#documentation-site) | `Done` | Built with Jekyll and published to GitHub Pages on every release. |
| [Releases](development.md#releases) | `Done` | Signed release APK built and published from a version tag. [0.1.0](https://github.com/drehtuer/NextAlarmApp/releases/tag/v0.1.0) published on 2026-11-07 as a pre-release; certificate SHA-256 `6aa7a127…be73210d`. |
| [Dependency updates and security](development.md#dependency-updates-and-security) | `Done` | Dependabot, CodeQL, OpenSSF Scorecard, [security policy](SECURITY.md). |

## Device checks

| Date       | Device                          | What was checked                                                       | Result |
|------------|---------------------------------|------------------------------------------------------------------------|--------|
| 2026-11-06 | Pixel 10a, Android 17 (API 37)  | Debug build from #5: install, launch, stays running, no crash or ANR. No alarm functions exist yet. | Pass   |
| 2026-11-07 | Pixel 10a, Android 17 (API 37)  | Phase 5 build: onboarding, including permissions, DAVx⁵ calendars listed, a planning run on a real DAVx⁵ calendar, and the empty state on home. | Pass   |
| 2026-11-07 | Pixel 10a, Android 17 (API 37)  | Silent device tests, build of `impl/device-tests`: a fired alarm rings at volume 0 and its screen opens by itself; Snooze; Dismiss; several alarms together; the bundled and default sounds open; Android's XML parser reads feeds and refuses entity tricks. | Pass   |
| 2026-11-07 | Pixel 10a, Android 17 (API 37)  | Silent alarm through the real `AlarmManager`, with the process dead and the screen off: the foreground service was allowed, with type `systemExempted`; the alarm screen woke the phone over the lock screen; the player ran with no audio hardening mute, only the test's own volume 0. | Pass   |
| 2026-11-07 | Pixel 10a, Android 17 (API 37)  | Build of `impl/audio-and-calendars`: the database upgrade to version 3 kept the chosen calendar; home lists real timed events from a DAVx⁵ calendar, registered with `AlarmManager` at the right times, with the matching "Next alarm" notification; a phone sound ("Prism") chosen through Android's sound picker is imported; **first audible sound: the play button on screen 5 plays it** (owner). A real alarm has not been heard yet. | Pass   |
| 2026-11-08 | Pixel 10a, Android 17 (API 37)  | **First real alarm** (owner): the alarm fired and its sound was audible. | Pass   |
| Postponed  | Pixel 10a, Android 17 (API 37)  | Phase 5 build with a timed event to test with: events on home, moved and deleted events, the next alarm notification. | Open   |

## Phase 0: Settle the concept gaps

| Step | Topic              | Status        | Notes |
|------|--------------------|---------------|-------|
| 0.1  | Moved events       | `Done`        | Moved event = `Remove` + `Create`; `Reschedule` removed. |
| 0.2  | Snapshot updates   | `Done`        | New event `Refresh(snapshot)`. |
| 0.3  | Recovery           | `Done`        | Recovery sweep in architecture.md. |
| 0.4  | Test alarm         | `Done`        | Key `test:<id>`, left out of the diff. |
| 0.5  | Events in progress | `Done`        | Horizon applied in the planner; device check in 9.2. |

## Phase 1: Project setup

| Step | Topic                 | Status        | Notes |
|------|-----------------------|---------------|-------|
| 1.1  | Gradle and modules    | `Done`        | Merged in #5. |
| 1.2  | Build configuration   | `Done`        | Merged in #5. |
| 1.3  | Test setup            | `Done`        | Merged in #5. |
| 1.4  | Coverage, CI, SonarCloud | `Done`        | Merged in #5. |

## Phase 2: Domain

| Step | Topic                 | Status        | Notes |
|------|-----------------------|---------------|-------|
| 2.1  | Domain types          | `Done`        | Merged in #5. |
| 2.2  | Planner               | `Done`        | Merged in #5. |
| 2.3  | Plan diff             | `Done`        | Merged in #5. |
| 2.4  | Planner state machine | `Done`        | Merged in #5. |
| 2.5  | Alarm state machine   | `Done`        | Merged in #5. |

## Phase 3: Data

| Step | Topic                                   | Status        | Notes |
|------|-----------------------------------------|---------------|-------|
| 3.1  | Room database                           | `Done`        | Merged in #7. |
| 3.2  | `SettingsRepository`, `AlarmRepository` | `Done`        | Merged in #7. |
| 3.3  | `CalendarRepository`                    | `Done`        | Merged in #7. Run on a real DAVx⁵ calendar (device checks); aCalendar and ICSx⁵ are still to do in 9.2. |
| 3.4  | `AlarmScheduler`                        | `Done`        | Merged in #7. |
| 3.5  | Alarm sound storage                     | `Done`        | Merged in #7. |

## Phase 4: Planning pipeline

| Step | Topic              | Status        | Notes |
|------|--------------------|---------------|-------|
| 4.1  | `PlanWorker`       | `Done`        | Merged in #10. |
| 4.2  | Worker triggers    | `Done`        | Merged in #10. |
| 4.3  | Broadcast triggers | `Done`        | Merged in #10. |

## Phase 5: Setup and home screens

| Step | Topic                     | Status        | Notes |
|------|---------------------------|---------------|-------|
| 5.1  | Visual design, navigation | `Done`        | Merged in #8, #9 and #11: app icon, notification icon `ic_stat_alarm`, Compose theme and navigation. |
| 5.2  | Onboarding                | `Done`        | Merged in #11. |
| 5.3  | Alarm calendar selection  | `Done`        | Merged in #11. |
| 5.4  | Home                      | `Done`        | Merged in #11. The fail-safe banner's "See what went wrong" button comes with step 8.4. |
| 5.5  | Basic notifications       | `Done`        | Merged in #11. "Open diagnostics" comes with step 8.4; tapping the warning opens the app. |

## Phase 6: Ringing

| Step | Topic                  | Status        | Notes |
|------|------------------------|---------------|-------|
| 6.1  | `AlarmReceiver`        | `Done`        | Merged in #12. |
| 6.2  | `AlarmService`         | `Done`        | Merged in #12. |
| 6.3  | Alarm screen           | `Done`        | Merged in #12. |
| 6.4  | Snooze and timeout     | `Done`        | Merged in #12. |
| 6.5  | Direct boot, recovery  | `Done`        | Merged in #12. |
| 6.6  | Late and missed alarms | `Done`        | Merged in #12. |

## Phase 7: Wake-up automation

| Step | Topic                | Status        | Notes |
|------|----------------------|---------------|-------|
| 7.1  | `WakeUpService`      | `Done`        | Merged in #13. |
| 7.2  | Speak the title      | `Done`        | Merged in #13. |
| 7.3  | Wake-up screen       | `Done`        | Merged in #13. |
| 7.4  | RSS news             | `Done`        | Merged in #13. |
| 7.5  | Podcast news         | `Done`        | Merged in #13. |
| 7.6  | News limits, failure | `Done`        | Merged in #13. |

## Phase 8: Remaining screens

| Step | Topic                   | Status        | Notes |
|------|-------------------------|---------------|-------|
| 8.1  | Settings                | `Done`        | Merged in #14. |
| 8.2  | Alarm settings          | `Done`        | Merged in #14. |
| 8.3  | News source             | `Done`        | Merged in #14. |
| 8.4  | Diagnostics and history | `Done`        | Merged in #14. |
| 8.5  | History cleanup         | `Done`        | Merged in #14. |

## Phase 9: Device verification

| Step | Topic              | Status        | Notes |
|------|--------------------|---------------|-------|
| 9.1  | Instrumented tests | `In progress` | Merged in #15. Silent suite passes on the phone. Direct boot needs a reboot, so it needs the owner. |
| 9.2  | Calendar sources   | `Not started` |       |
| 9.3  | Reliability        | `Not started` |       |
| 9.4  | Sound and wake-up  | `In progress` | A real alarm rang audibly on 2026-11-08 (device checks). The other checks in TODO.md are still to do. |
| 9.5  | Everyday use       | `Not started` |       |

## Phase 10: After the MVP

| Step | Topic                | Status        | Notes                  |
|------|----------------------|---------------|------------------------|
| 10.1 | Quick settings tile  | `Done`        | Merged in #18. Not yet seen in a real System UI. |
| 10.2 | Hue: silent alarm decision | `Not started` | Answers Q-H5.    |
| 10.3 | Hue: bridge pairing  | `Not started` |                        |
| 10.4 | Hue: scene recall    | `Not started` | Answers Q-H4.          |
| 10.5 | Hue: lead time       | `Not started` |                        |
| 10.6 | Hue: silent alarm    | `Not started` |                        |
