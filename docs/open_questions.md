# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default. Once a
question is answered, move the decision into the relevant document and remove it here.

## Reliability

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-R1 | An emergency alarm (one rung because its stored alarm could not be read) that is snoozed gets a "Snoozed" notification, whose Dismiss goes by the stored alarm. That is still `Scheduled`, so the Dismiss does nothing, or fails if the database is still down. The alarm then rings at the end of the snooze, where its Dismiss works. Should that Dismiss cancel the registration on its own? | Leave it: it needs two failures in a row, and the alarm can still be stopped when it rings. |
| Q-R2 | `stopWakeUp` goes by the stored state, so with an unreadable database Stop cannot end a running automation. It ends at its maximum duration. Should it go by `WakeUpDirector` as a last resort, as `sendToRinging` goes by the ringer? | Yes, when this is next worked on. It isn't alarm-critical: it is media volume and has a limit. |
| Q-R3 | `PlannerEngine.applyPlan` stops at the first failing command, so one failure leaves the later alarms of that run uncreated until the retry. Should each command be handled on its own, like the sweep? | Yes. The run should still count as failed. |
| Q-R4 | Opening the app does not start a planning run. On the phone, a revoked calendar permission only showed up as `FailSafe` at the next trigger, and a granted one only cleared it at the next retry of the backoff, which can be up to an hour away. Should opening the app (home coming to the front) start a run? | Yes: a run is cheap, and home then shows the real state. |
| Q-R5 | Before the first unlock after a reboot, the phone's default alarm sound can't be read, so the bundled sound rings instead (seen on the phone). A picked file is already copied into device-protected storage. Should the default sound be copied there as well, so the same sound rings? | Yes, if Android lets the app read the default sound's file; otherwise leave it, as the bundled sound is the documented fallback. |

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W2 | An alarm that rings while the news of an earlier one still plays is heard together with it, as the news is only stopped by a new title to speak. The alarm now takes the audio focus for a moment, which pauses other apps' audio, but the wake-up audio only stops when it loses the focus for good, so it plays on. Should a ringing alarm silence it, by treating that moment like a call? | Leave it: it is rare, and the alarm is what has to be heard. |
| Q-W3 | A test on the news screen and a running wake-up automation are not aware of each other, so both can be heard if the user starts a test while the automation plays (it can run without its screen). Should a test be refused then? | Leave it: the user has just asked for it, and Stop ends either. |
| Q-W4 | The "News" row on screen 5 names the feed's host, as the feed's own title ("Der Tag") isn't stored and storing it would change the schema. Should it be stored? | Leave it until the schema changes for another reason. |
| Q-W5 | The news starts about a quarter of a second after the title has been spoken, so the two run together (seen on the phone). Should there be a pause and an announcement before the news? | Yes: a pause of about two seconds, then "Here is the news from", followed by the feed's title. |
| Q-W6 | When the news can't be loaded (no network, an unreadable feed), the automation ends silently after the title, which sounds like a fault. Should the failure be said? | Yes: a short spoken "The news isn't available", then the end. |
| Q-W7 | The title and an RSS feed are spoken in the TTS engine's default language, so a German feed is read with an English voice. How is the language chosen? | Use the feed's declared language (`<language>`, `xml:lang` or `dc:language`) for the news and the phone's language for the title, with a setting on screen 6 to choose the language or voice for feeds that don't declare one or declare it wrongly. |
| Q-W8 | The maximum news duration can only be set in steps of 5 minutes, from 5 minutes. Should it be set by the minute? | Yes: 1 to 60 minutes in steps of 1. |
| Q-W9 | Should the wake-up automation also say a short weather forecast, for the current location or for a chosen place? It needs a weather service that is compatible with F-Droid (no key, no proprietary library, e.g. Open-Meteo), and the current location needs a location permission and has to work before the first unlock. | Yes, after the news, as its own step with its own on/off setting. A chosen place is the default; "current location" uses the last known coarse location and falls back to the chosen place. |

## Philips Hue (after the MVP)

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-H4 | The bridge limits the transition time of a recall. Is that enough for long ramps (e.g. 30 min), or does the app have to step the brightness itself? | Test against the bridge during implementation; step manually only if needed. |
| Q-H5 | A second, silent alarm turns the light off, but every event in the alarm calendars is a normal alarm and event fields carry no format. How is a silent alarm marked? | A separate calendar, chosen in the Hue settings, whose events only turn the light off. This keeps the rule that titles and descriptions have no format. |
