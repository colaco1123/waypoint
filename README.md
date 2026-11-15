# Waypoint

A personal life organiser for Android: tasks, daily routine, health and money in one AMOLED-black app,
with optional two-way sync to a Google Sheet so an AI assistant (or you) can edit everything from the sheet.

Built with Kotlin, Jetpack Compose (Material 3), Room, WorkManager, Glance widgets and the Google Sheets API.

## Features

- **Today**: overdue, due today and coming up tasks, plus your day drawn as a route
  (wake, workout, meals, habit reminders, bedtime) with tick-offs.
- **Tasks**: buckets, chained steps that unlock in order, dormant projects you activate later,
  recurring tasks (daily, weekly on chosen days, monthly, every N days/weeks/months), swipe to complete or snooze.
- **Health**: food log with a built-in Indian food list, calories and protein, water, sleep, weight,
  and a **Daily route** heatmap where each day fills green as you complete your routine.
- **Money**: monthly budget per category with 80% / 100% alerts, plus **automatic expense capture**
  from bank / UPI SMS and Google Pay / PhonePe / Paytm / Samsung Wallet notifications into a review queue
  that learns merchant categories.
- **Reminders**: exact alarms for tasks, morning briefing, workout, meals, water, habits, wind-down and
  bedtime, with Done / Snooze actions; they survive reboots and mirror to smartwatches.
- **Widgets**: Today (4x3), Water (2x2) and Money (3x2).
- **Sync**: optional Google Sheet with a README tab describing every column; newer `updated_at` wins.
  Works fully offline without it. JSON import / export for backups.

## Privacy

Data stays on the phone unless you link your own Google Sheet. Expense capture keeps only amount,
merchant, time, last 4 account digits and UPI ref; message text, OTPs and full account numbers are never stored.

## Build

See [BUILD.md](BUILD.md) for building, signing, the one-time Google Cloud setup for Sheets sync,
and details of every feature. A signed APK is attached to the latest release.

Note: Android may show "App blocked to protect your device" (Play Protect) for sideloaded apps that
request SMS access. Install over USB with `adb install`, or see BUILD.md.
