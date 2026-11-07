# Security policy

## Supported versions

NextAlarm has no public distribution yet. Security fixes go into `main` and the next release; older releases are not
patched.

## Reporting a vulnerability

Please do **not** open a public issue for a vulnerability. Report it privately through GitHub instead:
**[Report a vulnerability](https://github.com/drehtuer/NextAlarmApp/security/advisories/new)** (the **Security** tab of
the repository, then **Report a vulnerability**).

Include what an attacker can do, the steps or input to reproduce it, and the version or commit you tested.

This is a project maintained in spare time, so replies are best effort, without a fixed timeline. Once a fix is
released, the report is published as a security advisory, crediting you if you wish.

## Scope

In scope, for example:

- feeds and podcasts: the app downloads and parses feeds from servers the user does not control (see
  [Feeds](architecture.md#feeds)), so anything that gets past its limits on XML, size, addresses or time;
- a way for another app on the phone to start, stop, snooze or read alarms through NextAlarm's components;
- data of the app (the alarm calendars' events, settings, history) leaking to other apps or off the phone;
- a dependency of the app with a known vulnerability that is reachable from NextAlarm.

Not security issues, but welcome as normal issues: an alarm that does not ring or rings at the wrong time, and other
reliability bugs. Attacks that need a rooted or unlocked phone, or physical access to an unlocked phone, are out of
scope.
