# NextAlarm

> An Android alarm clock that is controlled by your calendar.

The stock Android alarm clock rings whether or not you have a holiday, are on vacation, or need to get up early for a flight. NextAlarm takes its alarms from a dedicated alarm calendar instead: alarms are set, moved and deleted in your calendar app, and the app rings them.

**Status:** early implementation. See [STATUS](docs/STATUS.md).

## Goals

- **All alarms come from one alarm calendar:** every event in the alarm calendar is an alarm at the event's start time. There is no alarm schedule inside the app; regular alarms are recurring events. To drop an alarm (holiday, vacation), delete it in the calendar app; aCalendar, for example, can delete instances of a recurring event automatically while a parallel event exists.
- **Wake-up automation:** after the alarm is dismissed (no need to unlock the phone), speak the event title and optionally read the news aloud.
- **Fail-safe:** if the calendar can't be read or planning fails, the alarms that are already planned ring anyway.

## How it works

The app reads events from the Android calendar provider (`CalendarContract`). The alarm calendar can therefore be any calendar on the device, for example:

- a local calendar in aCalendar (never synced)
- a Nextcloud / CalDAV calendar via [DAVx⁵](https://www.davx5.com/)
- a subscribed calendar (iCal/webcal) via [ICSx⁵](https://icsx5.bitfire.at/)

The calendar provider is a local store on the phone, so planning needs no network access. NextAlarm does not use the Google Clock app, calendar reminders or Google services; it schedules its own alarms.

Planning flow:

1. Collect the events of the next 48 hours (rolling window) from the alarm calendar.
2. Every event is an alarm. There are no veto rules and no description format.
3. Schedule exact alarms with `AlarmManager.setAlarmClock()`.

Planning is re-run whenever calendar data changes, so edits and sync updates are picked up automatically.

## Setting an alarm

Create an event in the alarm calendar at the desired wake-up time. The event title (e.g. `Flight to Hamburg, leave at 6:15`) is shown on the alarm screen and spoken after the alarm. The description and all other event fields are ignored. Alarms ring at the event's absolute start time, so travelling doesn't move them: an event at 07:00 Berlin time rings at 01:00 local time in New York. The app shows times in the phone's time zone.

Sound, vibration, snooze and the wake-up automation are configured once in the app and apply to every alarm. The alarm sound is the phone's default alarm sound, or an audio file (MP3, WAV, OGG or M4A) chosen with the Android file picker. All-day and cancelled events in the alarm calendar are ignored. Events are never merged, even if they are seconds apart.

## Requirements

- Target device: Google Pixel 10a. Android only; there is no iPhone variant and no Wear OS app.
- Android 17 (API 37) or newer
- Kotlin and Jetpack Compose; English UI only
- Calendar read permission
- Exact alarm permission (`USE_EXACT_ALARM`), notifications and full-screen notifications for the alarm screen

## Roadmap

- [ ] Calendar reading and planner
- [ ] Exact alarm scheduling, rescheduling after reboot and time zone changes
- [ ] Alarm screen with snooze
- [ ] Text-to-speech for the event title
- [ ] News readout (RSS / podcast)

Later, after the MVP:

- [x] Quick settings tile showing the next alarm
- [ ] Philips Hue: recall a scene from the Hue app with ramp-up, maximum brightness and a lead time for a sunrise effect

There is no public distribution yet. If the app is published later, it will be on F-Droid, so the build uses no proprietary dependencies.

## Documentation

- [Architecture](docs/architecture.md): modules, planning, re-planning triggers, ringing, direct boot, data model
- [Screens](docs/screens.md): screens, navigation and notifications
- [Design](docs/design.md): colours, type, shapes, wording and states of every screen
- [State machines](docs/state_machines.md): planner and alarm state machines with complete transition tables
- [Open questions](docs/open_questions.md): points the concept leaves open, with proposed defaults
- [Development](docs/development.md): dev container, SDK versions, running on the phone
- [TODO](docs/TODO.md): the implementation steps, in order
- [Status](docs/STATUS.md): the implementation status of each step

## License

[GPL-3.0](LICENSE)
