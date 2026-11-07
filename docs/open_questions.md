# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default. Once a
question is answered, move the decision into the relevant document and remove it here.

## Alarm settings

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-S1 | The low-volume banner in [design.md](design.md) (screen 5) suggests "or set a maximum volume below". The maximum volume is only the player's own gain and the system's stream volume is never changed, so it can only make an alarm quieter. What should the banner say instead? | Drop that part: "Alarms may be too quiet to wake you. Turn it up in the phone's sound settings." Settle it before step 8.2. |

## Wake-up automation

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-W1 | The wake-up audio does not ask for audio focus, so it does not pause other media and talks on during a phone call. Should it? | Ask for focus (`AUDIOFOCUS_GAIN`) and stop the automation when it is lost for good. Decide after using it for a few mornings in step 9.5. |
| Q-W2 | An alarm that rings while the news of an earlier one still plays is heard together with it, as the news is only stopped by a new title to speak. Should a ringing alarm silence it? | Leave it: it is rare, and the alarm is what has to be heard. |

## Philips Hue (after the MVP)

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-H4 | The bridge limits the transition time of a recall. Is that enough for long ramps (e.g. 30 min), or does the app have to step the brightness itself? | Test against the bridge during implementation; step manually only if needed. |
| Q-H5 | A second, silent alarm turns the light off, but every event in the alarm calendar is a normal alarm and event fields carry no format. How is a silent alarm marked? | A second calendar, chosen in the Hue settings, whose events only turn the light off. This keeps the rule that titles and descriptions have no format. |
