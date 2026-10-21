# NextAlarm

> An Android alarm clock that is controlled by your calendar.

The stock Android alarm clock rings whether or not you have a holiday, are on vacation, or need to get up early for a flight. This app reads your calendars and decides each day whether to ring, when, and how, using rules you define.

**Status:** concept / early planning. No code yet.

## Goals

- **Calendar vetoes:** an event in a holiday or vacation calendar disables the alarm for that day.
- **Name-based vetoes:** events whose title matches a pattern (e.g. `Vacation`, `Urlaub`) disable the alarm. Plain text or regular expressions.
- **All alarms come from the calendar:** an event whose description starts with `Alarm` becomes an alarm at the event's start time, configured by its description. There is no alarm schedule inside the app; regular alarms are recurring calendar events.
- **Categories with their own rules:** e.g. travel alarms ignore vacation vetoes, weekend alarms are gentler.
- **Wake-up automation:** after the alarm, speak the reason for the alarm and optionally read the news aloud.
- **Fail-safe:** if the calendar can't be read or a rule fails, the alarms that are already planned ring anyway.

## How it works

The app reads events from the Android calendar provider (`CalendarContract`). It therefore works with any calendar that syncs to the device, for example:

- Nextcloud / any CalDAV server via [DAVx⁵](https://www.davx5.com/)
- Subscribed public holiday calendars (iCal/webcal) via [ICSx⁵](https://icsx5.bitfire.at/)
- Any calendar app that uses the system calendar, such as aCalendar

The calendar provider is a local store on the phone, so planning needs no network access. NextAlarm does not use the Google Clock app, calendar reminders or Google services; it schedules its own alarms.

Planning flow:

1. Collect the events of the next 48 hours (rolling window) from the selected calendars.
2. Take the `Alarm` events as alarms.
3. Apply veto rules (veto calendars, title patterns). Finer rules, such as deleting a recurring alarm while a vacation event runs in parallel, are left to the calendar app (aCalendar supports this).
4. Schedule exact alarms with `AlarmManager.setAlarmClock()`.

Planning is re-run whenever calendar data changes, so late edits and sync updates are picked up before the alarm time.

## Scheduling an alarm from the calendar

Create an event at the desired wake-up time with a description like:

```
Alarm
sound: Birdsong
vibrate: yes
category: travel
reason: Flight to Hamburg, leave at 6:15
```

| Key        | Meaning                                              |
|------------|------------------------------------------------------|
| `sound`    | Alarm sound to play                                  |
| `vibrate`  | `yes` / `no`                                         |
| `category` | Rule category (alternatively: one calendar per category) |
| `reason`   | Shown on the alarm screen and spoken after the alarm |

The format is a draft and may change.

## Categories

Android's calendar provider does not expose CalDAV `CATEGORIES`. Categories are therefore defined either by:

- **calendar:** one calendar per category (e.g. Work, Travel, Personal), or
- **description:** a `category:` line in the event description.

Each category can have its own rules: whether vetoes apply, sound, vibration, and post-alarm actions.

## Requirements

- Target device: Google Pixel 10a. Android only; there is no iPhone variant and no Wear OS app.
- Android 17 (API 37) or newer
- Kotlin and Jetpack Compose; English UI only
- Calendar read permission
- Exact alarm permission (`USE_EXACT_ALARM`), notifications and full-screen notifications for the alarm screen

## Roadmap

- [ ] Calendar reading and rule engine
- [ ] Exact alarm scheduling, rescheduling after reboot and time zone changes
- [ ] Alarm screen with snooze
- [ ] Description parser for `Alarm` events
- [ ] Per-category rules
- [ ] Text-to-speech for the alarm reason
- [ ] News readout (RSS / podcast / radio stream)

There is no public distribution yet. If the app is published later, it will be on F-Droid, so the build uses no proprietary dependencies.

## Documentation

- [Architecture](docs/architecture.md): modules, planning, re-planning triggers, ringing, direct boot, data model
- [Screens](docs/screens.md): screens, navigation and notifications
- [State machines](docs/state_machines.md): planner and alarm state machines with complete transition tables
- [Open questions](docs/open_questions.md): points the concept leaves open, with proposed defaults

## License

[GPL-3.0](LICENSE)
