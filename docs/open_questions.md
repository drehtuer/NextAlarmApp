# Open questions

Questions the project description ([README.md](../README.md)) leaves open. Each has a proposed default. Once a
question is answered, move the decision into the relevant document and remove it here.

## Alarm settings

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-S1 | The low-volume banner in [design.md](design.md) (screen 5) suggests "or set a maximum volume below". The maximum volume is only the player's own gain and the system's stream volume is never changed, so it can only make an alarm quieter. What should the banner say instead? | Drop that part: "Alarms may be too quiet to wake you. Turn it up in the phone's sound settings." Settle it before step 8.2. |

## Philips Hue (after the MVP)

| ID   | Question | Proposed default |
|------|----------|------------------|
| Q-H4 | The bridge limits the transition time of a recall. Is that enough for long ramps (e.g. 30 min), or does the app have to step the brightness itself? | Test against the bridge during implementation; step manually only if needed. |
| Q-H5 | A second, silent alarm turns the light off, but every event in the alarm calendar is a normal alarm and event fields carry no format. How is a silent alarm marked? | A second calendar, chosen in the Hue settings, whose events only turn the light off. This keeps the rule that titles and descriptions have no format. |
