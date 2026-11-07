# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default. Once a
question is answered, move the decision into the relevant document and remove it here.

## Reliability

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-R1 | An emergency alarm (one rung because its stored alarm could not be read) that is snoozed gets a "Snoozed" notification, whose Dismiss goes by the stored alarm. That is still `Scheduled`, so the Dismiss does nothing, or fails if the database is still down. The alarm then rings at the end of the snooze, where its Dismiss works. Should that Dismiss cancel the registration on its own? | Leave it: it needs two failures in a row, and the alarm can still be stopped when it rings. |
| Q-R2 | `stopWakeUp` goes by the stored state, so with an unreadable database Stop cannot end a running automation. It ends at its maximum duration. Should it go by `WakeUpDirector` as a last resort, as `sendToRinging` goes by the ringer? | Yes, when this is next worked on. It isn't alarm-critical: it is media volume and has a limit. |
| Q-R3 | `PlannerEngine.applyPlan` stops at the first failing command, so one failure leaves the later alarms of that run uncreated until the retry. Should each command be handled on its own, like the sweep? | Yes. The run should still count as failed. |

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W2 | An alarm that rings while the news of an earlier one still plays is heard together with it, as the news is only stopped by a new title to speak. The alarm now takes the audio focus for a moment, which pauses other apps' audio, but the wake-up audio only stops when it loses the focus for good, so it plays on. Should a ringing alarm silence it, by treating that moment like a call? | Leave it: it is rare, and the alarm is what has to be heard. |
| Q-W3 | A test on the news screen and a running wake-up automation are not aware of each other, so both can be heard if the user starts a test while the automation plays (it can run without its screen). Should a test be refused then? | Leave it: the user has just asked for it, and Stop ends either. |
| Q-W4 | The "News" row on screen 5 names the feed's host, as the feed's own title ("Der Tag") isn't stored and storing it would change the schema. Should it be stored? | Leave it until the schema changes for another reason. |

## Philips Hue (after the MVP)

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-H4 | The bridge limits the transition time of a recall. Is that enough for long ramps (e.g. 30 min), or does the app have to step the brightness itself? | Test against the bridge during implementation; step manually only if needed. |
| Q-H5 | A second, silent alarm turns the light off, but every event in the alarm calendar is a normal alarm and event fields carry no format. How is a silent alarm marked? | A second calendar, chosen in the Hue settings, whose events only turn the light off. This keeps the rule that titles and descriptions have no format. |
