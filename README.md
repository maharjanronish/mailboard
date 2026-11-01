# Departa

[![Build APK](https://github.com/maharjanronish/mailboard/actions/workflows/build.yml/badge.svg)](https://github.com/maharjanronish/mailboard/actions/workflows/build.yml)

A calendar that behaves like a railway departure board. **Events are trains. Calendars are lines.**

- Board-first Android app with a tiny footprint: **zero external dependencies, no androidx, no network stack**
- Reads and writes the system calendar provider; Google Calendar stays in sync via Android's own sync adapter
- Departure-board UI: amber monospace on black, live clock in the header, departed rows struck through
- **Date rail** — slide one finger across the days under the fixed amber reticle (like a scope dial); the board scopes to that day and `+ NEW` defaults to it
- Home-screen widget showing the next 8 departures

## Build

1. Install Android Studio (Narwhal or newer) with **JDK 17**.
2. Open the `Departa` folder → let Gradle sync → **Run ▶**.
3. CLI alternative: `./gradlew assembleDebug` → APK in `app/build/outputs/apk/debug/`.

Toolchain: AGP 8.11.1 · Gradle 8.13 · Kotlin 2.1.21 · compileSdk/targetSdk 36 · minSdk 26 (Android 8.0+).
Release builds run R8 + resource shrinking (expect an APK well under 1 MB).

## First run

- Grant calendar access. The manifest has **no INTERNET permission** — data physically cannot leave the device.
- Every calendar already on the phone (Google or otherwise) instantly appears as a line.
- Slide the date rail (or tap `TODAY`) to scope the board to any day — 60 days back, 18 months forward.
- `+ NEW` files a new departure on the scoped day. `LINES` manages lines: create, rename, show/hide.

## How lines map to Google Calendar

| Line type | Storage | Syncs |
|---|---|---|
| Google line | a real calendar in your Google account | yes, two-way |
| Local line | a calendar under a device-local account | never leaves the phone |

Creating a Google line uses the standard sync-adapter insert pattern, then nudges a manual
sync so it shows up on google.com/calendar quickly. If your provider ever rejects
app-created calendars, create the calendar once in Google Calendar — it appears as a line
automatically. Moving an event to a different line re-files it (delete + insert), which
always syncs reliably.

## Widget

Long-press the home screen → Widgets → **Departa Board** (4×2, resizable). Rows refresh
every 30 minutes and immediately after any change made in the app. Tap a row to open the
event; tap the header to open the board.

## Version 1 limits (deliberate)

- Editing a recurring event edits the whole series; recurrence presets only (daily / weekly / monthly).
- Declined invitations are still shown.
- All-day events appear as `--:--` rows.
- No calendar deletion inside the app — delete lines in Google Calendar if really unwanted.

## Roadmap

Flip-in row animations · sub-lines via hidden extended properties (`Work → Meetings`) ·
reminders · AMOLED green theme · search & starred "pinned destinations".

## Project layout

```
app/src/main/java/com/departa/
  BoardActivity.kt        the board (day-scoped query, clock, date rail, ticker, observer)
  EditActivity.kt         create / edit a departure
  LinesActivity.kt        line manager
  board/DateRail.kt       self-drawn date "scope dial" (Canvas + OverScroller snap)
  board/BoardAdapter.kt   rows: departure / NOW divider
  data/                   models + CalendarContract repository (all storage access)
  ui/LineDialogs.kt       shared line-creation dialog
  util/                   time formatting, app-local prefs
  widget/                 4×2 board widget (provider + RemoteViews factory)
```

See `DESIGN.md` for the full design record: concept mapping, decisions, algorithms,
data contracts and performance budget.
