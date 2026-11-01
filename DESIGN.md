# Departa — Design Record

## 1. Concept mapping

| Railway | Departa |
|---|---|
| Station board | main screen |
| Departure | event instance |
| Line | calendar |
| Destination | event title |
| Platform | event location |
| Departure time | event start |
| Departed train | event whose end has passed |

## 2. Decision records

### D1 — A line IS a calendar
Google Calendar has no per-event "category" field. The candidates were event colors
(no names, limited), title prefixes (fragile), hidden extended properties (invisible
in Google's UI), and **multiple calendars**. Chosen: calendars. A line gets a real
name, a real color, native two-way sync, and the user's existing calendars become
lines for free. Local lines use `ACCOUNT_TYPE_LOCAL` calendars — identical board
behavior, zero cloud. Sub-lines (v2+) will layer hidden extended properties on top,
never replace this.

### D2 — No network, ever
The Android sync adapter already mirrors Google Calendar into the local provider.
Departa speaks only `ContentResolver` to that provider — no OAuth, no API keys, no
quotas, no Retrofit, and the manifest declares **no INTERNET permission**. CRUD is
local writes; the system syncs behind us. Pull-to-refresh equivalent: a one-line
`requestSync()` nudge after mutations.

### D3 — Zero dependencies, framework UI
Plain `Activity` + framework `Theme.Material` + `ListView` + one background
executor. No androidx, no Compose, no coroutines, no JSON. Kotlin was chosen over
Java for half the code at identical runtime performance (same JVM bytecode; the
R8-shrunk stdlib costs a few hundred KB).

### D4 — Board semantics
The board shows *departures* (starts), not running events: a 30-minute tail of
just-departed trains (struck through, dimmed), the NOW divider before the first
future departure, then the upcoming horizon.

## 3. Architecture

```
┌──────────────┐   Android sync adapter    ┌──────────────────┐
│ Google Cloud │ ◄──── (system, not us) ─► │ CalendarProvider │
└──────────────┘                           │    (SQLite)      │
                                           └────────┬─────────┘
                                                    │ ContentResolver only
                                    ┌───────────────┼────────────────┐
                                    ▼               ▼                ▼
                              BoardActivity     EditActivity     Widget factory
                              windowed query    insert/update    next-8 query
                              + observer        + delete/move    (push-refreshed)
```

## 4. Data contracts

**Lines** — `Calendars` table: `_ID`, `CALENDAR_DISPLAY_NAME`, `CALENDAR_COLOR`,
`ACCOUNT_NAME`, `ACCOUNT_TYPE`. Visibility filter is app-local (SharedPreferences),
never the global `VISIBLE` flag, so hiding a line here doesn't mutate the user's
Google Calendar state.

**Departures** — `Instances` table (provider already expands RRULEs): `_ID`,
`EVENT_ID`, `TITLE`, `BEGIN`, `END`, `ALL_DAY`, `CALENDAR_ID`, `EVENT_LOCATION`,
queried with appended begin/end path segments and `CALENDAR_ID IN (visible lines)`.

**Writes** — events via plain `Events` inserts/updates (dirty flag → sync). Calendar
creation uses the `CALLER_IS_SYNCADAPTER` URI pattern, followed by a manual
`requestSync` nudge. Moving an event across lines = delete + insert (the one
operation regular apps can't do in place).

## 5. Algorithms & techniques

| # | Technique | Where |
|---|---|---|
| 1 | Windowed instance query (indexed range scan) — O(log n + k) | `loadDepartures` |
| 2 | Multi-line merge done by SQLite `ORDER BY BEGIN` (k-way merge for free) | `loadDepartures` |
| 3 | Lazy horizon extension: +1 day chunks while scrolling, 60-day cap, cooldown guard | `extendWindow` |
| 4 | NOW divider & day separators in a single pass over sorted data | `buildRows` |
| 5 | Minute-aligned ticker (drift-free scheduling, no per-second wakeups) | `scheduleTick` |
| 6 | Debounced ContentObserver (350 ms) — coalesces sync bursts, registered only while resumed | `observer` |
| 7 | Token-guarded async loads — stale results dropped | `reload` |
| 8 | ListView recycling + per-view-type holders (3 types) | `BoardAdapter` |
| 9 | Widget push-refresh (`notifyAppWidgetViewDataChanged`) instead of polling | `BoardWidgetProvider.refresh` |

## 6. Row anatomy

```
───────────────── ⏱ NOW · 14:07 ─────────────────
 14:32  ● WORK   STANDUP SYNC            in 3m
                WORK · MEET LINK
 14:45  ● GYM    LEG DAY — PUSH             NOW
                GYM · STUDIO 2
──────────────── TOMORROW ────────────────
 09:00  ● GYM    MORNING RUN                45m
```

States: upcoming (`in Xm`), active (`NOW`), departed (struck through, 35% alpha,
`GONE`), all-day (`--:--` / `ALL DAY`).

## 7. Performance budget

| Metric | Target |
|---|---|
| Cold start | < 150 ms |
| Board re-query | < 5 ms (indexed SQLite scan) |
| RAM | < 40 MB |
| Release APK | ~0.5–1 MB |
| Permissions | 2 (calendar) — 0 network |
| External dependencies | 0 |

## 8. v1 scope & deferred

In: board, fast CRUD with repeat presets, line create/rename/hide, local lines, widget.
Deferred: flip animations, sub-lines (extended properties), reminders, all-day date
grouping, declined-event filtering, series-single-instance editing, i18n.
