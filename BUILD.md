# Waypoint: build and setup

Kotlin + Jetpack Compose (Material 3), Room, WorkManager, exact alarms, Google Sheets API v4.
Package `com.shay.waypoint`, min SDK 26, target SDK 35.

## Install the APK

1. Copy `Waypoint-1.0.apk` to the phone and open it (allow "Install unknown apps" for your file manager).
2. On first launch, allow notifications, then tap **Exempt** on the battery prompt
   (Samsung pauses background apps otherwise; reminders would arrive late).
3. Smartwatch: in your watch's companion app, turn on notifications for **Waypoint**.
   Settings > Notifications > *Send test notification* checks the watch mirroring.

Everything works offline. Google Sheets sync is optional (below).

## Build it yourself

Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0), Gradle 8.9 (or Android Studio).

```
# local.properties
sdk.dir=C\:\\Users\\<you>\\dev-tools\\android-sdk
```

Release signing reads `keystore.properties` in the project root:

```
storeFile=waypoint-release.jks
storePassword=...
keyAlias=waypoint
keyPassword=...
```

Then:

```
gradle assembleRelease      # or ./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

**Back up `waypoint-release.jks` and `keystore.properties`.** Updates must be signed with the
same key or Android refuses to install them over the existing app (you would have to uninstall
and lose local data).

## Google Sheets sync (one-time Google Cloud setup)

Google only issues Sheets tokens to apps registered in a Google Cloud project. Without this,
"Sign in" shows *DEVELOPER_ERROR*; the rest of the app still works.

1. Go to https://console.cloud.google.com/ and create a project (e.g. "Waypoint").
2. **APIs & Services > Library**: enable **Google Sheets API**.
3. **APIs & Services > OAuth consent screen**: User type *External*, app name Waypoint,
   your email as support + developer contact. Under **Test users**, add your Google account.
   (Leave it in *Testing*; no verification needed for personal use.)
4. **APIs & Services > Credentials > Create credentials > OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.shay.waypoint`
   - SHA-1: the release key fingerprint (printed in `SHA1.txt` next to this file, or run
     `keytool -list -v -keystore waypoint-release.jks -alias waypoint`)
5. Wait a few minutes, then in the app: Settings > Google account and sheet >
   **Sign in and create 'Waypoint Data'** (or link an existing sheet by URL).

Note: with the consent screen in *Testing*, Google may ask you to sign in again every 7 days
(Settings > Re-sign in). Publishing the consent screen to *In production* removes that limit.

## How sync works

- Triggers: refresh button (top bar), pull to refresh, app open, every 30 min in background,
  and ~20 s after any local edit.
- Every row has a text `id` and an ISO `updated_at`. Per row, the newer `updated_at` wins.
  Equal stamps: the sheet wins unless the phone has unsynced edits to that row.
- `deleted = TRUE` removes the row on the phone. Deleted rows stay in the sheet for 30 days.
- New sheet rows with a blank `id` get one assigned and written back.
- Malformed rows are skipped, kept in the sheet unchanged, and listed in Settings > Data > Sync log.
- Linking an *existing* sheet that already has data replaces the phone's data with the sheet's.
- The created sheet has a README tab describing every column, so a Claude chat with the
  Google Sheets connector can edit it correctly. Remind Claude to set `updated_at` to now.

## Today route and Health route chart

- The Today route = wake, workout, leave, meal slots, bedtime, plus **one stop per `reminder_times`
  entry of every Habits row** (Skincare `07:30,22:45` = two stops), sorted by time.
- Ticking a habit stop logs to HabitLog: workout sets the day to its minimum minutes; any other
  habit adds 1 per ticked stop (water = +1 glass). Each reminder time also fires a notification
  (Done / +1 glass, Snooze 15 min); stops already ticked don't re-notify.
- Health > Daily route: each day is a square split into one section per route stop, filling green
  from the bottom as stops are done; a complete day is solid green. Tap a day to see which stops
  were done. Days before the app was installed show as blank.

## Automatic expense capture (Money > To review)

Settings > Auto expense capture:

- **SMS capture**: asks for RECEIVE_SMS + READ_SMS, then offers to scan the last 30 days. Only
  senders in the editable list (HDFCBK, SBIUPI, ICICIB, AXISBK, KOTAKB, ...) are parsed, and only
  successful debits (debited / spent / paid / sent / withdrawn). Credits, OTPs, balance alerts,
  promos and failed transactions are ignored.
- **Notification capture**: Google Pay, PhonePe, Paytm and Samsung Wallet "Paid ₹X to Y"
  notifications (needs Notification access; the toggle opens that screen).
- **Android 13+ sideloading**: if a permission is greyed out as "restricted", open
  App info > ⋮ > **Allow restricted settings**, then try again.
- Captures are de-duplicated by UPI ref, or same amount within 2 minutes (SMS vs app notification).
- Each capture lands in **To review** with a suggested category: tick = add to Expenses
  (`source` = sms / notification), tap the category to change it, swipe = dismiss.
- Confirming teaches a rule in the **MerchantRules** tab (`match_text` -> `category`);
  "Auto-confirm known merchants" (off by default) skips review when a rule matches.
- Stored per capture: amount, merchant, time, last 4 account digits, UPI ref. Message text, OTPs
  and full account numbers are never stored. The review queue stays on the phone (not synced).

## Home-screen widgets

Long-press the home screen > Widgets > Waypoint:

- **Today (4x3):** due count, water, streak, next routine stop, due tasks (tap a task to complete
  it), `+ TASK` (opens Quick Capture) and `+1 GLASS`.
- **Water (2x2):** glasses vs goal as a segmented gauge; tap anywhere for +1 glass.
- **Money (3x2):** budget left this month, segmented gauge, top category; `+ ₹` opens expense entry.

Widgets update live from the database (any change in the app, a notification action, or a sync)
and refresh every 30 min for the date rollover. On Samsung, add them from One UI's widget picker
the same way.

## Import / export (offline backup)

Settings > Data > Import JSON accepts:

```json
{"ops":[
  {"action":"add","table":"Tasks","row":{"title":"Book train","bucket":"Life","due_date":"2026-11-20"}},
  {"action":"update","table":"Tasks","row":{"id":"t-0012","priority":"high"}},
  {"action":"complete","table":"Tasks","row":{"id":"t-0003"}},
  {"action":"delete","table":"Expenses","row":{"id":"e-0004"}}
]}
```

It shows a preview ("adds 1, updates 1, completes 1, deletes 1") and applies on confirm.
Export JSON copies the full state (`{"tables":{...}}`), which Import also accepts as a restore.

## Code map

- `data/` Room store (one generic row table mirroring the sheet tabs), `Schema` (headers and
  validation), `Repo` (all business logic: chains, recurrence, habits, budget alerts, import/export), `Seed`, `Foods`.
- `sync/` `SheetsApi` (REST client), `SyncEngine` (merge), `SyncWorker` (WorkManager), `Auth`.
- `notify/` channels, exact-alarm `Scheduler`, alarm/action/boot receivers.
- `ui/` Compose screens: Today, Tasks, Health, Money, Settings, plus Quick Capture and editors.
