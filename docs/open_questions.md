# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default, which the
[architecture](architecture.md) currently assumes. Once a question is answered, move the decision into the
relevant document and remove it here.

## Platform

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-P2 | Kotlin, Jetpack Compose and Room are decided. Which dependency injection (Hilt, Koin, manual)? It must be F-Droid compatible. | Hilt (open source). |

## Rules and planning

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-R8 | Should cancelled events in the alarm calendar be ignored? | Yes, ignore `STATUS_CANCELED`. |
| Q-R10 | Two events close to each other in the alarm calendar (e.g. recurring 06:30 and a one-off 06:35): keep both or merge? | Keep both; merge only if less than 1 minute apart. |

## Scheduling and sync

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-S2 | The app cannot tell whether DAVx⁵ / ICSx⁵ synced recently. Should stale calendars be detected (e.g. no change for N days) and warned about? | Show the last calendar change time in diagnostics; no automatic warning. |
| Q-S3 | Is a pre-alarm check (re-plan shortly before the alarm) wanted, and how long before? | Yes, 30 minutes before each alarm. |
| Q-S4 | Should changes after the alarm has started (ringing/snoozed) have any effect? | No; see [state machines](state_machines.md#alarm-state-machine). |
| Q-S5 | Time zones: is an alarm event in another zone (travel) interpreted in the event's zone or the device's? | The event's absolute start instant; display in device zone. |

## Alarm behaviour

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-A1 | Snooze length and maximum number of snoozes? | 9 min, max 3 (configurable in alarm settings). |
| Q-A2 | Ring timeout without interaction: auto-snooze or stop? How long? | Ring 5 min, then auto-snooze; after max snoozes stop and mark `Done`. |
| Q-A3 | Grace period for alarms that fire late (device was off): ring or mark as missed? | Ring if at most 60 min late, else `Missed` + notification. |
| Q-A4 | An alarm fires while another one is ringing. | Defer the second one by 1 minute. |
| Q-A5 | Gentle wake-up: volume ramp, maximum volume, vibration first? | Configurable volume ramp duration and maximum volume. |
| Q-A6 | Any dismiss challenge (math puzzle, shake) to prevent dismissing while asleep? | No. |

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W1 | When does the automation start: on dismiss, or only after the user unlocks the phone? | On dismiss. |
| Q-W2 | TTS language and voice: device default or configurable? What is said besides the event title (time, weather)? | Device default; "Good morning, it is 6:30. Flight to Hamburg, leave at 6:15." |
| Q-W3 | News: which formats are needed first (RSS read via TTS, podcast episode, radio stream)? Maximum duration? | Latest podcast episode or radio stream, max 15 min. RSS later. |
| Q-W4 | Audio output for wake-up: alarm stream or media stream? | Media stream, so the volume keys work as expected. |

## UI

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-U1 | Should there be a home screen widget or quick settings tile (next alarm)? | Not in the first version. |
| Q-U2 | Upcoming-alarm notification (information only, alarms are changed in the calendar app): wanted? How long before? | Yes, 2 hours before (like the stock clock). |
| Q-U3 | Export / import of settings (backup, new phone)? | Android Auto Backup for settings; no manual export in the first version. |
