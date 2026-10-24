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

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W2 | TTS language and voice: device default or configurable? What is said besides the event title (time, weather)? | Device default; "Good morning, it is 6:30. Flight to Hamburg, leave at 6:15." |
| Q-W3 | News: which formats are needed first (RSS read via TTS, podcast episode, radio stream)? Maximum duration? | Latest podcast episode or radio stream, max 15 min. RSS later. |
| Q-W4 | Audio output for wake-up: alarm stream or media stream? | Media stream, so the volume keys work as expected. |

## Philips Hue (after the MVP)

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-H1 | Use a scene from the Hue app, or let NextAlarm set colour and brightness itself? | Scene from the Hue app, with NextAlarm's ramp duration and maximum brightness applied on recall. |
| Q-H2 | When does the light start: at the alarm time, or before it (sunrise effect)? | Configurable lead time, default 0 (start with the alarm). |
| Q-H3 | What does "stop" do: turn the light off on Dismiss, after the wake-up automation, after a delay, or never? | Leave the light on; optional "turn off after N minutes". |
| Q-H4 | The bridge limits the transition time of a recall. Is that enough for long ramps (e.g. 30 min), or does the app have to step the brightness itself? | Verify against the bridge; step manually only if needed. |
