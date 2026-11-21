# Implementation status

The progress of each step in [TODO.md](TODO.md). Update this file in the same change that starts or finishes a step.

**Last updated:** 2026-11-21

**Summary:** Phases 0 to 8, the silent device tests and the quick settings tile are merged. Several alarm calendars, a settable alarm volume, audio focus, call handling and http only for podcasts are merged in #20. The documentation is published as a GitHub Pages site with every release. On 2026-11-20 most of phase 9 was run on the phone with the owner; every check passed, and the run found that "Sync now" never reached DAVx⁵, which is fixed. Still open: aCalendar (9.2), everyday use (9.5) and the tile in a real System UI (10.1). On 2026-11-21 the open reliability and wake-up questions were answered and the weather added (10.8 to 10.10), and checked on the phone; only the pause before the weather is still to be heard. Hue is waiting for Q-H5.

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
| [Releases](development.md#releases) | `Done` | Signed release APK built and published from a version tag. Pre-releases: [0.1.0](https://github.com/drehtuer/NextAlarmApp/releases/tag/v0.1.0) on 2026-11-07, [0.2.0](https://github.com/drehtuer/NextAlarmApp/releases/tag/v0.2.0) (Sync now, security fixes) on 2026-11-08. Certificate SHA-256 `6aa7a127…be73210d`. |
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
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | Debug build of `main` (replaces release 0.2.0): silent suite, 13 tests. On a fresh install, `aFiredAlarmRingsAndDismissEndsIt` failed until onboarding had granted the notification permission; the test now grants it itself. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | The postponed phase 5 check: a timed event in the DAVx⁵ "Alarms" calendar is registered within seconds with the "Next alarm" notification; moved, it is registered at the new time only; deleted, its registration is gone. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 9.2: `Instances` also returns an event that started before the query range (range start = event start + 1 s), so the planner's horizon is what keeps it out. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 9.3 and 9.4 (owner): alarm under Doze with Do Not Disturb (priority) on and the phone locked rings audibly, with the full-screen alarm screen over the always-on display. Deep `IDLE` could not be forced (`QUICK_DOZE_DELAY`), so this ran under quick Doze. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 9.4 (owner): after Dismiss on the lock screen, the title is spoken, a real RSS feed (Tagesschau) is read, and Stop in the lock screen's media controls ends it; a real podcast (Tagesschau in 100 Sekunden) plays and ends at the end of the episode. With airplane mode on, the alarm and title work and the news ends quietly. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 9.3 (owner): with the calendar permission revoked, a planned alarm still rings; the next run ends in `FailSafe` (`NO_PERMISSION`) with the warning, and once the permission is back, the backoff retry plans again and the warning goes. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 9.3 and 9.4 (owner): after a reboot, never unlocked, the alarm rings with the bundled sound (the phone's default sound can't be read before the first unlock), and the title and the podcast follow. With the phone off at the alarm time, the alarm rings 3 minutes late at boot with the "Late alarm" notification (`lateBy` 184 s). The "missed" case (over 60 minutes) is left to the unit tests. | Pass   |
| 2026-11-20 | Pixel 10a, Android 17 (API 37)  | 10.7 (owner): "Sync now" never reached DAVx⁵: Android dropped the request because NextAlarm could see neither DAVx⁵ nor its account. Fixed with `<queries>` for sync adapters and Android's account chooser. After the fix, an event made in the Nextcloud web interface reached home with one tap, and the chooser came up once for an account NextAlarm could not see yet. | Pass   |
| 2026-11-21 | Pixel 10a, Android 17 (API 37)  | 10.8 to 10.10, build of #37 over the data of 0.2.0: the database upgrade from version 3 to 5 kept everything; opening the app started a planning run; the copy of the default sound ("Bright Morning", 230 KB) is in device-protected storage, and screen 5 shows no note; opening screen 6 stored the podcast's title; the duration steps by 1 minute; the language list has the phone's voices (English, German, Spanish); a search for "Berlin" found five places, and the preview said the forecast in English and in German; "Use the current location" asks for the approximate location and stays off when refused. | Pass   |
| 2026-11-21 | Pixel 10a, Android 17 (API 37)  | 10.9 and 10.10 (owner): the feed starts about 1 s after the title; a second alarm that rings stops the first one's audio, and its own title and feed follow; in airplane mode "The feed isn't available" is said and the weather is left out. The weather followed the feed without a pause, which is now added. | Pass   |
| 2026-11-21 | Pixel 10a, Android 17 (API 37)  | 10.8 (owner): after a reboot, never unlocked, the alarm rings with the phone's default sound ("Bright Morning") from its copy, where on 2026-11-20 the bundled sound rang. | Pass   |

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
| 3.3  | `CalendarRepository`                    | `Done`        | Merged in #7. Run on a real DAVx⁵ calendar (device checks); aCalendar is still to do in 9.2. |
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
| 9.1  | Instrumented tests | `Done`        | Merged in #15. Silent suite passes on the phone, also on a fresh install. Direct boot was checked by hand with a reboot (9.3). `AccountAccessDeviceTest` checks the account chooser, with someone at the phone. |
| 9.2  | Calendar sources   | `In progress` | DAVx⁵ and the `Instances` range checked on 2026-11-20. aCalendar is still to do; it is installed on the phone now. ICSx⁵ was dropped, as there is no use for it. |
| 9.3  | Reliability        | `Done`        | Checked on 2026-11-20 (device checks). Deep Doze could not be forced; the "missed" case is left to the unit tests. |
| 9.4  | Sound and wake-up  | `Done`        | Checked on 2026-11-20 (device checks). Improvements found are Q-W5 to Q-W9 and Q-R5 in [open questions](open_questions.md). |
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
| 10.7 | Sync now on home     | `Done`        | Checked on the phone on 2026-11-20, after fixing that Android dropped every request (device checks). |
| 10.8 | Reliability answers  | `Done`        | Q-R1 to Q-R5, merged in #37. Checked on the phone on 2026-11-21, also the default sound before the first unlock. |
| 10.9 | Wake-up answers      | `Done`        | Q-W2 to Q-W8, merged in #37. Checked on the phone on 2026-11-21. |
| 10.10 | Weather             | `In progress` | Q-W9, merged in #37. Checked on the phone on 2026-11-21; the pause between the feed and the weather is still to be heard. |
