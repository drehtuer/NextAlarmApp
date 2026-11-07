# Design

Status: delivered by Claude Design, ready to build. This document is the handover for implementing the visual design.
It gives every value and every piece of text the screens need. [Screens](screens.md) still defines what each screen does
and how they connect; this document defines how they look and what they say.

Sources, for reference only (both are private claude.ai artifacts of the project owner; this document is complete
without them):

- Design system **Cloudsync**, shared with NextGallery: https://claude.ai/artifact/X3aFsDXLL1qTFopup2nsA4
- Clickable prototype of all nine screens, with every state in its Tweaks panel, and a notifications board:
  https://claude.ai/artifact/Uzr6b5Y178x4HHngg47TNL

## Decisions

| Topic | Decision | Why |
|-------|----------|-----|
| Visual direction | Calm, native Material 3 on neutral greys; one brand blue; colour only where it carries meaning (next alarm, warnings, outcomes). | The app has to say "you can trust this" at a glance, mostly half asleep in a dark room. |
| Themes | Screens 1–7 follow the system light/dark setting (the phone runs dark). Screens 8 and 9 are always "night": true black, whatever the system setting. | Black has no glare in a dark room and saves power on the OLED screen. |
| Dynamic colour | Off. A fixed palette. | Warning and outcome colours must look the same on every wallpaper. |
| Snooze vs Dismiss | Snooze is the large outlined button in the middle of the lower half (112 dp tall). Dismiss is filled light blue, 80 dp tall, at the bottom, 48 dp below Snooze, with a stop icon. | They differ in fill, size, position and lightness. A sleepy thumb lands on Snooze, and an accidental snooze is far less harmful than an accidental dismiss. |
| Home grouping | Day headers "Today · Friday 6 November", "Tomorrow · Saturday 7 November", then "Sunday 8 November". | Easy to scan for a missing alarm. |
| Snoozed alarm on home | Shown: the hero card says "Snoozed", and the row has a chip "Snoozed until 06:10". | The user can see that an alarm is still pending. |
| Two alarms seconds apart | Two rows, never merged. A time with non-zero seconds shows them ("06:00:30"). The hero card says "Next alarm · 2 alarms at 06:00". | The brief requires that they are never merged. |
| Low alarm volume hint | Added to screen 5: a banner when the phone's alarm stream volume is at or below 2 of its steps. | A very quiet phone volume is the most likely reason an alarm fails to wake someone. |
| About | Screen 3 always ends with the version and a link to the source code. | Requested by the project owner. |
| Fonts | The system font (Roboto on the Pixel), through `FontFamily.Default`. No font files ship. | No downloads, nothing to license. |
| Icons | Inline vector drawables in the Material Symbols style (24 dp grid, 2 dp stroke, round caps). | Already open-licensed shapes; no icon font. |

## Colour

All values come from Cloudsync. Map them to `lightColorScheme()` / `darkColorScheme()` exactly; Material roles that are
not listed keep the Material 3 baseline derived from these.

### Material 3 roles

| Compose role | Light | Dark | Used for |
|--------------|-------|------|----------|
| `primary` | `#00639B` | `#99CBFF` | Filled buttons, section headers, links, switches |
| `onPrimary` | `#FFFFFF` | `#003354` | Text on primary |
| `primaryContainer` | `#CFE5FF` | `#004A76` | Next-alarm hero card, "Snoozed" chip, tonal buttons |
| `onPrimaryContainer` | `#001D33` | `#CFE5FF` | Text on primaryContainer |
| `secondary` | `#8A5100` | `#FFB870` | Rare warm accent |
| `onSecondary` | `#FFFFFF` | `#4A2800` | |
| `secondaryContainer` | `#FFDCBE` | `#693C00` | Warnings that are not errors: low volume, full-screen alarms denied, late, not answered |
| `onSecondaryContainer` | `#2C1600` | `#FFDCBE` | Text on secondaryContainer |
| `surface` / `background` | `#F8F8F8` | `#121212` | Screen background |
| `surfaceContainer` | `#EDEDED` | `#1E1E1E` | Alarm lists, empty states, permission cards |
| `surfaceContainerHigh` | `#E4E4E4` | `#2A2A2A` | Dialogs, menus, switch track when off, disabled button fill |
| `surfaceVariant` | `#E2E2E2` | `#303030` | Neutral outcome chip ("Event deleted") |
| `onSurface` / `onBackground` | `#1B1B1B` | `#E6E6E6` | Main text |
| `onSurfaceVariant` | `#474747` | `#C6C6C6` | Secondary text, icons in top bars |
| `outline` | `#767676` | `#929292` | Field and outlined-button borders, radio rings, switch thumb when off |
| `outlineVariant` | `#C6C6C6` | `#474747` | Dividers, progress tracks |
| `inverseSurface` | `#303030` | `#E6E6E6` | Snackbars |
| `inverseOnSurface` | `#F1F1F1` | `#303030` | Text on snackbars |
| `inversePrimary` | `#99CBFF` | `#00639B` | Snackbar action |
| `scrim` | `#000000` | `#000000` | Behind sheets and dialogs |
| `error` | `#A3161A` | `#FF8E84` | Error text and icons (sound file rejected, feed test failed) |
| `onError` | `#FFFFFF` | `#690005` | |
| `errorContainer` | `#FFDAD6` | `#93000A` | "New alarms aren't being added" banner, "No calendar chosen", "Last check failed", "Missed" chip |
| `onErrorContainer` | `#410002` | `#FFDAD6` | Text on errorContainer |

### Extra colours

Provide these through a `CompositionLocal` (for example `LocalNextAlarmColors`), with a light and a dark set like the
scheme.

| Name | Light | Dark | Used for |
|------|-------|------|----------|
| `success` | `#1E6E3C` | `#7FD99A` | "Allowed" next to a granted permission |
| `successContainer` | `#C8EFD2` | `#0B3D1E` | "Alarms are planned" card, "Dismissed" chip |
| `onSuccessContainer` | `#0B3D1E` | `#C8EFD2` | Text on successContainer |

### Night colours (screens 8 and 9, both themes)

| Name | Value | Used for |
|------|-------|----------|
| `nightSurface` | `#000000` | Background, edge to edge |
| `nightSurfaceContainer` | `#1A1A1A` | Snooze button fill, "now playing" card |
| `nightOutline` | `#474747` | Snooze button border (2 dp), playback track |
| `nightOnSurface` | `#E6E6E6` | Time, titles, Snooze label |
| `nightOnSurfaceVariant` | `#A8A8A8` | Date, "Rings again at …", secondary lines |
| `nightPrimary` | `#99CBFF` | Dismiss and Stop fill, playback progress |
| `nightOnPrimary` | `#003354` | Dismiss and Stop label |
| `nightBadge` | `#004A76` on `#CFE5FF` | Icon disc on the "now playing" card |

Every text colour above reaches 4.5:1 contrast on its background. `outline` on `surface` in light is 4.3:1, which is
enough because it is only used for borders (3:1).

## Type

`FontFamily.Default` for every style. Use `sp`, so the user's font size setting applies.

| Material role | Size / line height | Weight | Used for |
|---------------|--------------------|--------|----------|
| `displayLarge` | 112 / 120 | 400 | Alarm screen time; tabular figures; letter spacing −0.02 em |
| `displayMedium` | 72 / 80 | 400 | Next-alarm time on home; tabular figures |
| `displaySmall` | 64 / 72 | 400 | Wake-up screen time |
| `headlineMedium` | 32 / 40 | 400 | Onboarding titles; a single alarm title on screen 8 (weight 500) |
| `headlineSmall` | 26 / 34 | 500 | Several titles on screens 8 and 9 |
| `titleLarge` | 22 / 28 | 400 | Top bar titles, empty-state titles |
| `titleMedium` | 18 / 26 | 400 | Hero card title; "now playing" line (500) |
| `bodyLarge` | 16 / 24 | 400 | List rows, body text |
| `bodyMedium` | 14 / 20 | 400 | Secondary lines, banners |
| `bodySmall` | 13 / 18 | 400 | Footers ("Checked today at 19:58") |
| `labelLarge` | 14 / 20 | 500 | Buttons, section headers |
| `labelMedium` | 12 / 16 | 500 | Chips, field labels |

Times in lists use 20 / 28 at weight 500. Every time uses `FontFeatureSettings("tnum")`, so digits line up. A long
title on screen 8 drops to 24 / 32.

## Shape and spacing

| Token | Value | Used for |
|-------|-------|----------|
| `shapes.small` | 8 dp | Chips, text fields |
| `shapes.medium` | 12 dp | Icon tiles, small cards |
| `shapes.large` | 16 dp | Lists, banners, permission cards |
| `shapes.extraLarge` | 28 dp | Next-alarm hero card, empty states |
| Buttons | Fully rounded (pill) | Every button |
| Spacing | 4, 8, 12, 16, 24 dp | Gaps; screen side margin 16 dp for cards and 24 dp for text and rows |

Sizes:

- Touch targets are at least 48 dp. List rows are 56–72 dp. Top bars are 64 dp, and their icon buttons are 48 dp.
- Snooze is 112 dp tall and Dismiss 80 dp, both full width minus 24 dp margins, with 48 dp between them.
- Switches are 52 × 32 dp.

Edge to edge: every screen draws its background behind the status bar and the gesture bar, and pads its content with
`WindowInsets.safeDrawing`. On the Pixel 10a that is about 58 dp at the top and 24 dp at the bottom. Content scrolls
between the top bar and the bottom inset.

## Screens

Text in quotes is the exact wording. All examples assume "now" is Friday 6 November, 20:00.

### 1 Onboarding & permissions

Three steps, with a progress bar of three 4 dp segments at the top (done and current in `primary`, the rest in
`outlineVariant`). Bottom row: "Back" (text button, from step 2 on) and the main button on the right.

1. **Explanation.** Title "Your calendar sets the alarms". Lead "NextAlarm has no alarm editor. You plan alarms in your
   calendar app, and this phone rings them." Three rows, each a 40 dp `primaryContainer` icon disc with a bold line and
   a secondary line:
   - Calendar icon: "Every event is an alarm" / "Each timed event in the calendar you choose rings at its start time."
   - Repeat icon: "Regular alarms repeat" / "For a weekday alarm, make a recurring event. Skip a day by deleting that one
     occurrence."
   - Bin icon: "Change or delete in the calendar" / "Move an event and its alarm moves. Delete it and the alarm is gone."

   The main button is "Get started".
2. **Permissions.** Title "Allow three things". Lead "NextAlarm only reads the one calendar you choose next. Nothing
   leaves your phone." Below that, one `surfaceContainer` card per permission, with the title, a description, and on the
   right either an "Allow" button or a green check with "Allowed":
   - "Read your calendars" / "To see the events in your alarm calendar. Only that one calendar is read."
   - "Send notifications" / "To show the next alarm, and to ring when the phone is locked."
   - "Show alarms full screen" / "So a ringing alarm fills the screen, even when the phone is locked."

   When a permission is denied, a note appears inside its card with an outlined "Open app settings" button:
   - Calendar denied (`errorContainer`): "Calendar access was denied, so NextAlarm can't see any alarms and nothing will
     ring. Allow it in the app settings under Permissions."
   - Full-screen alarms denied (`secondaryContainer`): "Alarms will still ring, but only as a notification at the top of
     the screen. Snooze and Dismiss are then small buttons in that notification."
   - Notifications denied (`errorContainer`): "Notifications were denied, so a ringing alarm can't show its screen or its
     Snooze and Dismiss buttons. Allow them in the app settings under Notifications."

   Calendar access and notifications are required to continue, because a full-screen alarm is itself delivered as a
   notification. Full-screen alarms are optional.

   The main button is "Continue".
3. **Calendar.** Title "Choose the alarm calendar". Lead "Every timed event in it will ring. Use a calendar just for
   alarms, so meetings don't wake you." The same list as screen 4. The main button is "Done".

### 2 Home

- **Top bar:** "NextAlarm", with a settings icon button (sliders icon, content description "Settings").
- **Fail-safe banner** (`errorContainer`, warning icon, only in `FailSafe`):
  - Title: "New alarms aren't being added"
  - Body: "NextAlarm couldn't read your calendar at 19:58. The alarms below will still ring. Anything you add or change in
    the calendar won't, and if this lasts 48 hours, nothing rings."
  - Outlined button: "See what went wrong", which opens screen 7.
- **Hero card** (`primaryContainer`, 28 dp corners, 24 dp padding):
  - A label: "Next alarm", "Next alarm · 2 alarms at 06:00" or "Snoozed".
  - The time in `displayMedium`.
  - The relative day: "Today · in 2 h 30 min", "Tomorrow, Saturday · in 10 h" or "Rings again in 10 min".
  - The title, at most three lines.
- **Day groups:** a `labelLarge` header in `primary`, followed by one `surfaceContainer` list with 16 dp corners and
  `outlineVariant` dividers. Each row is the time (100 dp wide, so that a time with seconds fits) and the title, at most two lines. A snoozed row adds a
  `primaryContainer` chip: "Snoozed until 06:10".
- **Event without a title:** show "No title" in italics in `onSurfaceVariant`.
- **Empty state** (`surfaceContainer` card, calendar icon):
  - Title: "No alarms in the next 48 hours"
  - Body: "Nothing is planned until Sunday 20:00. To set one, add a timed event to the NextAlarm calendar in your calendar
    app."
- **Footer** (`bodySmall`, `onSurfaceVariant`): "From the calendar “NextAlarm” (DAVx⁵). Checked today at 19:58."

### 3 Settings

The top bar has a back button and "Settings". Each row has a title, a secondary line and a chevron:

| Row | Secondary line |
|-----|----------------|
| "Alarm calendar" | The calendar and its account ("NextAlarm · DAVx⁵"), or "None chosen. Nothing rings" |
| "Alarm settings" | "Sound, snooze, speech and news" |
| "Diagnostics & history" | "Last check 19:58 · all good", or "Last check failed" |
| "Run setup again" | "Explanation, permissions and calendar" |

Below the rows come a divider and a section headed "About". It is always present:

- "Version" with "Free software, GPL-3.0" underneath. The value on the right is `BuildConfig.VERSION_NAME` followed by the
  build number: "0.1.0 (build 1)".
- "Source code" with the link text "github.com/drehtuer/NextAlarmApp" underneath, in `primary` and underlined, with an
  external-link icon. Tapping the row opens `https://github.com/drehtuer/NextAlarmApp` in the browser
  (`Intent.ACTION_VIEW`).

### 4 Alarm calendar

- **Top bar:** "Alarm calendar".
- **Lead:** "Every timed event in this calendar rings at its start time. No other calendar is read."
- **Calendars:** grouped by account, with the account name as a `primary` header ("DAVx⁵ · me@cloud.example",
  "ICSx⁵ · Holidays", "On this phone"). Each row is a radio button, the calendar's own colour as a 12 dp square, and its
  name. The selected row has a `surfaceContainer` background.
- **None selected** (`errorContainer` banner): "**No calendar chosen.** Until you choose one, no alarm rings."
- **No calendars:**
  - Title: "No calendars on this phone"
  - Body: "Sync one from your Nextcloud with an app such as DAVx⁵, or create a local calendar in your calendar app. Then
    come back here."

### 5 Alarm settings

- **Top bar:** "Alarm settings".
- **Lead:** "These apply to every alarm."
- **Low-volume banner** (`secondaryContainer`, muted-speaker icon), shown when the alarm stream volume is at or below
  2 steps: "**Your phone's alarm volume is low (1 of 7).** Alarms may be too quiet to wake you. Turn it up in the phone's
  sound settings, or set a maximum volume below."
- **Section "Sound":**
  - "Alarm sound": "Phone default (<sound name>)" or the file name. On the right, an outlined "Choose file" button. When
    a file is set, a text button "Use the phone's default sound" appears below.
  - When a file is rejected, a line in `error` with an error icon appears below the sound row:
    - Unsupported type: "“Notes.pdf” isn't a sound file. Choose an MP3, WAV, OGG or M4A file."
    - Can't be read: "“morning.mp3” can't be opened. It may have been moved or deleted. Choose it again."
    - No playable audio: "“silence.m4a” has no sound NextAlarm can play. Choose another file."
  - "Volume ramp" / "Rises from silent", with the value on the right: "None" or a duration.
  - "Maximum volume" / "Not set: uses the phone's alarm volume", with the value "Not set" or a percentage.
  - "Vibration", with a switch.
- **Section "Ringing":**
  - "Ring for" / "Then it snoozes on its own": "3 min".
  - "Snooze length": "10 min".
- **Section "After you dismiss":**
  - "Speak the title" / "Reads the event title aloud", with a switch.
  - "News" / "Off", "RSS · tagesschau.de" or "Podcast · Der Tag", with a chevron that opens screen 6.

Values open the standard Material 3 pickers.

### 6 News

- **Top bar:** "News".
- **Lead:** "After you dismiss an alarm, NextAlarm can read an RSS feed aloud, or play the latest episode of a podcast."
- **Address field:** an outlined text field labelled "Feed address", with placeholder "https://…". The helper text is
  "Paste the address of an RSS or podcast feed.", then "RSS feed found." or "Podcast found." once one is detected.
- **Detected feed** (`surfaceContainer` card with a `primaryContainer` icon tile):
  - The kind: "RSS feed · read aloud" or "Podcast · latest episode plays".
  - The feed name.
  - A detail line: "12 headlines today" or "Deutschlandfunk · today, 18 min".
- **Duration:** "Maximum duration" / "Playback stops after this", with a − / + stepper showing "15 min".
- **Buttons:**
  - "Test playback": filled, with a play icon. It is disabled while the address is empty.
  - While the test plays, the button reads "Stop test", with a progress bar and "Playing “Der Tag” · 0:42 of 15:00".
  - "Turn off news": a text button.
- **Test failed:**
  - The field shows a 2 dp `error` border and the helper text "This address didn't return a feed."
  - An `errorContainer` banner reads: "**Test failed.** The server answered “404 Not Found”. Check the address, then try
    again. Until it works, alarms skip the news."

### 7 Diagnostics & history

- **Top bar:** "Diagnostics & history".
- **Status card**, one per planner state:
  - `Planned` (`successContainer`, check icon): "Alarms are planned" / "Last check today at 19:58 took 0.4 s and found
    4 alarms in the next 48 hours."
  - `Planning` (`primaryContainer`, refresh icon, indeterminate progress bar): "Checking the calendar…" / "Started at
    20:00. Alarms already planned stay in place."
  - `FailSafe` (`errorContainer`, warning icon): "Last check failed" / "Today at 19:58: calendar permission was removed.
    The 4 alarms already planned will still ring, but changes in the calendar are not picked up." The second sentence is
    followed by the matching fix, for example "Allow calendar access again to fix it."
- **Facts:** label–value rows: "Calendar", "Next check" ("When the calendar changes, and at least once a day") and "Full-screen alarms".
- **Buttons:** "Ring a test alarm" (tonal, bell icon) and "Export log" (outlined).
- **"Past alarms":** one row per alarm with the time ("Thu 07:00"), the title, and an outcome chip:

| Final state | Chip text | Colour |
|-------------|-----------|--------|
| `Done` | "Dismissed", or "Dismissed after 2 snoozes" | `successContainer`, check icon |
| `Done`, rang late | "Rang 7 min late (phone was off)" | `secondaryContainer`, clock icon |
| `Unanswered` | "Not answered (rang twice)" | `secondaryContainer`, warning icon |
| `Cancelled` | "Event moved or deleted before it rang" | `surfaceVariant`, cross icon |
| `Missed` | "Missed: phone was off" | `errorContainer`, cross icon |

### 8 Alarm screen (night)

From top to bottom, under the status-bar inset plus 48 dp:

1. The date in `nightOnSurfaceVariant`: "Saturday 7 November".
2. The time in `displayLarge`.
3. The titles, centred, in a scrollable area:
   - One alarm: `headlineMedium` at weight 500.
   - Several alarms: a line "3 alarms", then each title in `headlineSmall`.
   - A long title: 24 / 32, never truncated.
   - An event without a title: "Alarm (event has no title)" in italics, in `nightOnSurfaceVariant`.
4. **Snooze:** an outlined pill with the label "Snooze" and, underneath, "Rings again at 06:10".
5. **Dismiss:** a filled pill with a stop icon and "Dismiss".

Both buttons act on every ringing alarm. Dismiss stops the sound at once.

Details decided while building this screen:

- The time is at most `displayLarge`. A time with seconds doesn't fit at 112 sp ("06:00:30" would be about 420 dp wide,
  and 363 dp are available), so the time shrinks until it fits on one line (about 97 sp). Its line stays 120 sp high, so
  nothing else moves.
- A title is long above 60 characters: about three lines at the size of a single title.
- The date and the line "3 alarms" are `titleMedium`.
- The labels are larger than the `labelLarge` of other buttons, because this screen is read half asleep: "Snooze" is
  `headlineSmall`, "Rings again at 06:10" (inside the button) is `bodyLarge`, and "Dismiss" is `titleLarge` at weight
  500.
- The buttons are minimum heights: at a very large font size they grow rather than cut off their labels. Dismiss ends
  24 dp above the bottom inset. The buttons are always on screen, and only the titles scroll.
- "Rings again" gives the earliest time if the ringing alarms have different snooze lengths. Like every time, it shows
  seconds if they are not zero.

### 9 Wake-up screen (night)

1. "Good morning · Saturday 7 November", then the time in `displaySmall`.
2. The title or titles, centred, in `headlineSmall`.
3. A "now playing" card (`nightSurfaceContainer`, 24 dp corners) with an icon disc and a progress bar. Its contents
   depend on what is playing:

| Playing | Kind line | Main line | Times |
|---------|-----------|-----------|-------|
| Speech | "Speaking the title" (or "Speaking the titles") | The title being spoken, or "2 of 3: Take morning medication" | Seconds |
| RSS | "Reading the news · tagesschau.de" | "Headline 3 of 12" | "1:48" / "up to 15:00" |
| Podcast | "Podcast · Deutschlandfunk" | The episode title, for example "Der Tag, 6 November" | "4:12" / "18:30" |

4. A small line: "Unlocking the phone won't stop it."
5. **Stop:** a filled pill, the same as Dismiss.

## Notifications

All notifications use the standard Android layouts, the small icon `ic_stat_alarm`, and `primary` (`#00639B`) as the
accent colour (`setColor`).

| Notification | Title | Text | Actions |
|--------------|-------|------|---------|
| Next alarm (silent, ongoing) | "Next alarm: tomorrow 06:00" | The event title | none |
| Alarm | The event title ("Flight to Hamburg, leave at 6:15") | "Ringing now" | "Snooze", "Dismiss" |
| Snoozed | "Snoozed until 06:10" | The event title | "Dismiss" |
| Wake-up media | "Playing: Der Tag" | "Deutschlandfunk podcast · stops by 06:16 at the latest" | "Stop" |
| Fail-safe warning | "New alarms aren't being added" | "NextAlarm can't read your calendar. Alarms already planned will still ring." | "Open diagnostics" |
| Late alarm | "An alarm rang 7 min late" | "“Train to Cologne” was due at 06:45, but the phone was off." | "Open history" |
| Missed alarm | "An alarm didn't ring" | "“Brunch with Lea” at 09:00 was missed because the phone was off." | "Open history" |
| Alarm not answered | "An alarm wasn't answered" | "“Gym” at 06:30 rang twice with no response. It won't ring again." | "Open history" |

The relative day in "Next alarm" follows the home screen: "today", "tomorrow", or the weekday.

## Icons

- **Launcher:** `mipmap-anydpi/ic_launcher.xml`. An adaptive icon with a `primary` background and the "calendar bell"
  foreground. Its monochrome layer has the bell cut out of the calendar page.
- **Notification:** `drawable/ic_stat_alarm.xml`. A white 24 dp calendar bell.
- **In the app:** stroke icons on a 24 dp grid with a 2 dp stroke. You need: settings (sliders), back arrow, chevron,
  external link, calendar, repeat, bin, warning, error (circle with exclamation mark), check, muted speaker, bell,
  refresh, clock, cross, play, stop, microphone (podcast), RSS and speaker (speech). Icon-only buttons need a content
  description.

## Implementation notes

- Put the theme in the `app` module: `ui/theme/Color.kt`, `Type.kt`, `Shape.kt` and `Theme.kt`, plus the
  `CompositionLocal` with the extra and night colours. Screens read values only from the theme.
- Screens 8 and 9 wrap their content in a night theme: `darkColorScheme` with the night values, used whatever the
  system setting. Their activities also set a black window background in XML, so nothing flashes before Compose draws.
- The window theme (`themes.xml`, replacing `Theme.Material.NoActionBar`) has to match the Compose theme in both modes:
  the same background colours and transparent system bars.
- The low-volume check reads `AudioManager.getStreamVolume(STREAM_ALARM)` and `getStreamMaxVolume`, behind a thin
  interface. Put the threshold rule (`volume <= 2`) in `domain` and unit-test it there.
- Times and dates use the device time zone and the 24-hour format, as in the examples.
