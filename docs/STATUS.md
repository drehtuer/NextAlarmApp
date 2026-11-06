# Implementation status

The progress of each step in [TODO.md](TODO.md). Update this file in the same change that starts or finishes a step.

**Last updated:** 2026-11-06

**Summary:** Phases 0 to 3 are merged, and the design is delivered in [design.md](design.md). Phase 4 is implemented on the branch `impl/planning` and tested on the JVM, but not merged. The app still shows a placeholder screen, and alarms cannot ring until Phase 6.

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

## Device checks

| Date       | Device                          | What was checked                                                       | Result |
|------------|---------------------------------|------------------------------------------------------------------------|--------|
| 2026-11-06 | Pixel 10a, Android 17 (API 37)  | Debug build from #5: install, launch, stays running, no crash or ANR. No alarm functions exist yet. | Pass   |

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
| 3.3  | `CalendarRepository`                    | `Done`        | Merged in #7. Only tested against a fake provider; not yet run on real calendars. |
| 3.4  | `AlarmScheduler`                        | `Done`        | Merged in #7. |
| 3.5  | Alarm sound storage                     | `Done`        | Merged in #7. |

## Phase 4: Planning pipeline

| Step | Topic              | Status        | Notes |
|------|--------------------|---------------|-------|
| 4.1  | `PlanWorker`       | `In progress` | Not merged. |
| 4.2  | Worker triggers    | `In progress` | Not merged. |
| 4.3  | Broadcast triggers | `In progress` | Not merged. |

## Phase 5: Setup and home screens

| Step | Topic                     | Status        | Notes |
|------|---------------------------|---------------|-------|
| 5.1  | Visual design, navigation | `In progress` | App icon added: adaptive launcher icon with a monochrome layer, and the notification icon `ic_stat_alarm`. Theme and navigation still to do. |
| 5.2  | Onboarding                | `Not started` |       |
| 5.3  | Alarm calendar selection  | `Not started` |       |
| 5.4  | Home                      | `Not started` |       |
| 5.5  | Basic notifications       | `Not started` |       |

## Phase 6: Ringing

| Step | Topic                  | Status        | Notes |
|------|------------------------|---------------|-------|
| 6.1  | `AlarmReceiver`        | `Not started` |       |
| 6.2  | `AlarmService`         | `Not started` |       |
| 6.3  | Alarm screen           | `Not started` |       |
| 6.4  | Snooze and timeout     | `Not started` |       |
| 6.5  | Direct boot, recovery  | `Not started` |       |
| 6.6  | Late and missed alarms | `Not started` |       |

## Phase 7: Wake-up automation

| Step | Topic                | Status        | Notes |
|------|----------------------|---------------|-------|
| 7.1  | `WakeUpService`      | `Not started` |       |
| 7.2  | Speak the title      | `Not started` |       |
| 7.3  | Wake-up screen       | `Not started` |       |
| 7.4  | RSS news             | `Not started` |       |
| 7.5  | Podcast news         | `Not started` |       |
| 7.6  | News limits, failure | `Not started` |       |

## Phase 8: Remaining screens

| Step | Topic                   | Status        | Notes |
|------|-------------------------|---------------|-------|
| 8.1  | Settings                | `Not started` |       |
| 8.2  | Alarm settings          | `Not started` |       |
| 8.3  | News source             | `Not started` |       |
| 8.4  | Diagnostics and history | `Not started` |       |
| 8.5  | History cleanup         | `Not started` |       |

## Phase 9: Device verification

| Step | Topic              | Status        | Notes |
|------|--------------------|---------------|-------|
| 9.1  | Instrumented tests | `Not started` |       |
| 9.2  | Calendar sources   | `Not started` |       |
| 9.3  | Reliability        | `Not started` |       |
| 9.4  | Sound and wake-up  | `Not started` |       |
| 9.5  | Everyday use       | `Not started` |       |

## Phase 10: After the MVP

| Step | Topic                | Status        | Notes                  |
|------|----------------------|---------------|------------------------|
| 10.1 | Quick settings tile  | `Not started` |                        |
| 10.2 | Hue: silent alarm decision | `Not started` | Answers Q-H5.    |
| 10.3 | Hue: bridge pairing  | `Not started` |                        |
| 10.4 | Hue: scene recall    | `Not started` | Answers Q-H4.          |
| 10.5 | Hue: lead time       | `Not started` |                        |
| 10.6 | Hue: silent alarm    | `Not started` |                        |
