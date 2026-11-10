# Sojourn

Know where you've been, plan where you're going. An Android app for digital nomads, expats and
anyone whose taxes or visas depend on how many days they spend where.

## What it does

- **Counts days per country on its own.** A coarse location every few hours (never GPS), a check
  whenever the time zone changes, and the mobile network's country as a fallback. Or the mobile
  network only, with no location permission at all, or nothing in the background.
- **Each country counts days its own way.** Spain counts any moment on its soil, the UK where
  you are at midnight, Cyprus the day you arrive but not the day you leave, Korea from the day
  after arrival. Sojourn knows how about 60 countries count a travel day, works out the order of
  a travel day's countries (from times, or from the days around it), never counts airside
  transit, and lets you override any country under Settings › Counting.
- **Avoid or keep a tax residency, with every test a country has.** Cyprus's 183-day and 60-day
  rules, Ireland's 280 days over two years, the UK's statutory residence test with its ties,
  India's 60 + 365 days, the US substantial presence test, rolling 12-month windows and stays in
  a row (Germany's six months)… Tests that depend on facts only you know (a home, a job, the
  centre of your interests) ask you, and until you answer, say "resident if…". Each card shows
  one still meter with a mark per threshold. Spain also shows the count with sporadic absences
  (trips away and back, up to 183 days, unless you hold a residency certificate elsewhere), and
  can use it for the limit. Every country's rules cite their source and how sure Sojourn is.
- **Limits for wherever you're from.** Schengen 90/180, residence continuity (never away more than 6 months in a row, as EU
  long-term residence and citizenship require), visa-free stays, or any custom rule. Visitor
  limits know your passports and residence permits: an EU citizen is never counted against
  Schengen, and days in a country where you hold a permit don't count. For US citizens and
  residents there are the substantial presence and FEIE tests. Each rule shows days left, when
  days free up, how long you could stay from tomorrow, and sends a heads-up near the limit.
- **Plan the year ahead.** Planned stays are checked against every rule before you book: the
  editor shows a draft trip's effect live, and the year forecast mixes lived and planned days.
- **Flights.** Add a flight by number, date and airports (searched offline in a built-in list
  of the world's airports), or paste a boarding pass or booking confirmation to fill it in.
  Departure and arrival become travel days, the days between landing and your next flight out
  are planned there, and Today shows your next flight. No flight lookup service is involved.
- **Calendar.** A month at a time with flags, or the whole year as Grid, Compact or Rows (a row
  per month), coloured by country with travel days split corner to corner. Long-press and drag
  to select days, then set, add or take off a country on all of them, past or planned: a range
  inside a bigger one splits it, it never overlaps it. A day shows, for each country, whether
  that country counts it and why, and a travel day can be turned the other way round.
- **Timeline.** Stays between border crossings, newest first and opening at today: each with the
  days there, the days that country counts, and its tax-year total so far; each crossing with
  its date, route, transit and flight.
- **Import trips.** Flights in your calendars (like those Gmail adds from booking emails),
  boarding pass screenshots from Google Wallet or airline apps, PDF boarding passes, Apple
  Wallet passes, .ics files, single emails, or a whole mailbox exported from Google Takeout,
  whose schema.org markup gives flights, trains, buses, ferries and hotels. Set your name and
  bookings made for someone else are left out. Text and files can also be shared to Sojourn.
- **Fill in the past.** From where photos were taken, GPX tracks (Strava, Komoot, Garmin…) or a
  Google Timeline export: the on-device `Timeline.json`, Takeout's `Records.json` and Semantic
  Location History, the iOS export, or the whole Takeout zip. Everything is read on the phone,
  only countries are kept, and you approve each stay found.
- **Reports.** Travel history, every country and trip, for visa, residence and citizenship
  applications (or Global Entry). For US filers, Form 2555 (FEIE physical presence) with the best
  12-month period and the line 18 travel table as CSV.
- **Home screen widget** with the current country, days there this year and the closest limit.
- **Backups.** One readable JSON file to export or restore (merge or replace), CSV of every day,
  automatic weekly backups into any folder (e.g. one synced by Syncthing), and Android's own
  backup.

## Private by design

The app has no `INTERNET` permission. Countries are found on the phone from built-in Natural
Earth borders (`app/src/main/assets/borders.bin`, about 11 m precision), and only the country
is ever stored, never coordinates.

## Building

Everything builds in a container; the host needs only `make` and Docker or Podman.

```sh
make debug        # app/build/outputs/apk/debug/app-debug.apk
make test         # unit tests: day counting, rules, reports, country lookup, backups, file import
make screenshots  # renders the main screens to app/build/screenshots
make verify       # tests, debug build and lint
make release      # shrunk release APK, signed: sojourn-<date>.apk
```

Every build is numbered by its date and hour (`versionCode` 2026102513, `versionName`
2026.10.25), so each one installs as an update of the last. Debug builds are a separate app
(`app.sojourn.debug`) and can live beside a release.

### Signing

Debug builds are signed with `debug.keystore`, a public key kept in the repository on purpose:
Gradle would otherwise create a new key inside every build container, and Android refuses an
update signed with a different key.

Releases are signed with a private key that never enters the repository:

1. `make release-key` creates `signing/release.jks`, asking for a password (or taking it from
   `SOJOURN_KEYSTORE_PASSWORD`).
2. Back up `signing/release.jks` and its password somewhere safe. Losing either means no update
   can be installed over a release again.
3. `make release` builds and signs. The password comes from `SOJOURN_KEYSTORE_PASSWORD`, then
   from `signing/release.password` if that file exists, or is asked for.

`make geodata NE=…/ne_50m_admin_0_countries.geojson` rebuilds the border assets from a
[Natural Earth](https://www.naturalearthdata.com/) download, and `make icon` regenerates the
launcher, themed and notification icons from `tools/build_icon.py`.
`python3 -I tools/build_airports.py airports.csv app/src/main/assets/airports.tsv` refreshes the
airport list from [OurAirports](https://ourairports.com/data/) (public domain).

## Layout

- `domain/` — pure Kotlin: `DayBook` (days and the journey), `Counting` (how a country counts a
  day), `CountryRules` (what Sojourn knows per country), `Residency` (multi-test residency rules
  and Spain's sporadic absences), `RuleEngine`, `Reports`
- `data/travel/` — trip import: boarding pass barcodes (BCBP), schema.org reservations, MIME and
  mbox, iCalendar, calendar events, passenger names
- `data/` — Room database, DataStore settings, backup format, offline country lookup
- `tracking/` — the background check, alerts, automatic backups, photo scanning
- `ui/` — Compose with Material 3 Expressive; one package per screen
- `widget/` — the Glance home screen widget
