# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default, which the
[architecture](architecture.md) currently assumes. Once a question is answered, move the decision into the
relevant document and remove it here.

## Platform

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-P1 | The only target is the Pixel 10a, which ships with a newer Android than 13. Keep `minSdk = 33` as in the README, or raise it to the Pixel 10a's Android version to avoid compatibility code? | Keep 33; test only on the Pixel 10a. |
| Q-P2 | Tech stack: Kotlin + Jetpack Compose + Room + DataStore + WorkManager? Which dependency injection (Hilt, Koin, manual)? | Compose, Room, DataStore, WorkManager, Hilt. |
| Q-P3 | Distribution: Google Play, F-Droid, or only self-built APKs? Play has a policy review for `USE_EXACT_ALARM`; F-Droid requires no proprietary dependencies. | F-Droid-compatible build (no Google Play Services). |
| Q-P4 | UI languages: English only, or also German (the examples use `Urlaub`)? | English and German. |
| Q-P5 | Is a Pixel Watch / Wear OS companion in scope? | No. |

## Rules and planning

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-R1 | Planning horizon: the README says "tomorrow's events". What about an alarm at 00:30, or planning on a Friday for Monday? | Rolling horizon: now + 48 h, extended to the next scheduled alarm. |
| Q-R2 | "Fall back to the default alarm schedule if nothing applies": does an `Alarm` event replace the default alarm of that day or add to it? And does a vetoed day fall back to the default (it should not)? | An `Alarm` event replaces the default alarm for that day. A veto never causes a fallback. |
| Q-R3 | Veto scope: does a timed event (e.g. "Urlaub" from 14:00) veto the morning alarm? How are multi-day events handled? | A veto applies to every local date the event overlaps, regardless of time of day. |
| Q-R4 | Does a veto affect only the default alarm, or also `Alarm` events of categories that honour vetoes? | Both; the category's "honours vetoes" switch decides. Default category honours vetoes. |
| Q-R5 | Which category does the default alarm have? | A configurable default category ("Default"). |
| Q-R6 | Are veto patterns matched against the title only, or also description and location? | Title only. |
| Q-R7 | Regex flavour and flags: case-insensitive by default? Full match or substring? | Kotlin `Regex`, case-insensitive, substring match (`containsMatchIn`). |
| Q-R8 | Should cancelled events, events marked "free", or events the user declined be ignored? | Ignore `STATUS_CANCELED` and declined events; "free" events still count. |
| Q-R9 | Category conflicts: an event in a category calendar *and* with a `category:` line. Which wins? Unknown category names? | `category:` line wins; unknown name → default category + warning. |
| Q-R10 | Two alarms close to each other (e.g. default 06:30 and event 06:35): keep both or merge? | Keep both; merge only if less than 1 minute apart. |
| Q-R11 | Can the user add a one-off manual alarm in the app, or only via the calendar? | Calendar only; the app only skips. |

## `Alarm` description format

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-D1 | "Description starts with `Alarm`": case-sensitive? Must it be on its own line? Localised keyword (`Wecker`)? | First non-empty line equals `Alarm`, case-insensitive. No localised keyword. |
| Q-D2 | Alarm at the event's start time only, or should an offset be supported so the event can be the flight itself (`offset: -2h`)? | Add an optional `offset:` key. |
| Q-D3 | How are sounds named (`sound: Birdsong`)? System ringtones by title, sounds bundled with the app, user files? | System alarm sounds by title, plus bundled sounds; unknown → default sound + warning. |
| Q-D4 | Behaviour on unknown keys or invalid values (e.g. `vibrate: maybe`)? | Ignore the line, keep defaults, show a warning in day details. The alarm is still created. |
| Q-D5 | Should other keys be supported (volume, snooze, news on/off)? | Not in the first version. |

## Scheduling and sync

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-S1 | What exactly rings in fail-safe mode? Default schedule only, or also the alarms of the last successful plan? What if the default schedule is "off" on that day? | Last successful plan's alarms ∪ default schedule (vetoes ignored). If both are empty, nothing rings, and the warning notification says so. |
| Q-S2 | The app cannot tell whether DAVx⁵ / ICSx⁵ synced recently. Should stale calendars be detected (e.g. no change for N days) and warned about? | Show the last calendar change time in diagnostics; no automatic warning. |
| Q-S3 | Is a pre-alarm check (re-plan shortly before the alarm) wanted, and how long before? | Yes, 30 minutes before each alarm. |
| Q-S4 | Should changes after the alarm has started (ringing/snoozed) have any effect? | No; see [state machines](state_machines.md#alarm-state-machine). |
| Q-S5 | Time zones: is an `Alarm` event in another zone (travel) interpreted in the event's zone or the device's? | The event's absolute start instant; display in device zone. |

## Alarm behaviour

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-A1 | Snooze length and maximum number of snoozes, per category? | Per category; 9 min, max 3. |
| Q-A2 | Ring timeout without interaction: auto-snooze or stop? How long? | Ring 5 min, then auto-snooze; after max snoozes stop and mark `Done`. |
| Q-A3 | Grace period for alarms that fire late (device was off): ring or mark as missed? | Ring if at most 60 min late, else `Missed` + notification. |
| Q-A4 | An alarm fires while another one is ringing. | Defer the second one by 1 minute. |
| Q-A5 | "Weekend alarms are gentler": what does gentler mean? Volume ramp, quieter, vibration first? | Per-category volume ramp duration and maximum volume. |
| Q-A6 | Any dismiss challenge (math puzzle, shake) to prevent dismissing while asleep? | No. |

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W1 | When does the automation start: on dismiss, or only after the user unlocks the phone? | On dismiss. |
| Q-W2 | TTS language and voice: device default or configurable? What is said besides `reason` (time, weather)? | Device default; "Good morning, it is 6:30. Flight to Hamburg, leave at 6:15." |
| Q-W3 | News: which formats are needed first (RSS read via TTS, podcast episode, radio stream)? Maximum duration? | Latest podcast episode or radio stream, max 15 min. RSS later. |
| Q-W4 | Audio output for wake-up: alarm stream or media stream? | Media stream, so the volume keys work as expected. |

## UI

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-U1 | Should there be a home screen widget or quick settings tile (next alarm, skip)? | Not in the first version. |
| Q-U2 | Upcoming-alarm notification with "Skip": wanted? How long before? | Yes, 2 hours before (like the stock clock). |
| Q-U3 | Export / import of settings (backup, new phone)? | Android Auto Backup for settings; no manual export in the first version. |
