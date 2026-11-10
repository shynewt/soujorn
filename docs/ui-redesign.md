# Sojourn UI redesign

Branch `ui-refactor`. Goal: one clear place for each thing, every count explainable, and an app
whose purpose — knowing where you stand and being able to prove it — shows in its structure.

## What was wrong (audit, Nov 2026)

- Core deliverables (import, evidence pack, travel history, Form 2555, backup) hid under
  "Tools" at the bottom of Today.
- "Add a flight" existed only in Plan's FAB; past flights couldn't be added from the day.
- Evidence lived in five places that didn't link: day sheet, flight editor, evidence screen,
  rule card bar, import result.
- Timeline and Calendar were two tabs over the same days, with different abilities (Timeline's
  day sheet had no evidence and no undo).
- The same country got different colours on Home, Plan and Calendar.
- Undo only after Calendar edits; Recent changes buried under Backup.
- Text pointed at places without linking ("Settings › Counting", "Plan › Flights", "Import…").
- Names drifted: Passports/Citizenship, Assumed/striped/Fill gaps, Limits & residency/Rules/Manage.

## Structure

Three tabs, each answering one question. (A fourth, Plan, was removed after use: its upcoming
flights and stays were the Calendar list's "Ahead", its rule effects were already on the rule
cards, and adding a stay or flight lives on Today's + and the day sheet. Its year forecast moved
to the Calendar's Year view.)

| Tab | Question | Holds |
| --- | --- | --- |
| **Today** | Where do I stand? | Where you are now, what needs attention, rule status, next flight, this year by country |
| **Calendar** | What happened? | The record of days: month, year and list (the old Timeline) views; day sheet with countries, flights and evidence |
| **Evidence** | Can I prove it? | The vault per year: coverage, documents by group, a checklist of period documents, export and reports |

Off the bar, opened from where they're needed:

- **Settings** (gear on Today): Tracking · Counting · About you · Rules · Notifications ·
  Appearance · Your data (recent changes, backup, restore, spreadsheet, erase).
- **Rules** (Today's rule cards, Plan's limits ahead, Settings): a rule opens straight in its editor.
- **Import** (Today's add menu, Evidence, empty states, Settings › Your data, share sheet).
- **Reports** under Evidence: evidence pack, travel history, Form 2555.

## Today

1. **Now** — the hero: flag, country, day N of the stay, tracking state; "Still in X" /
   "Somewhere else" when today isn't recorded yet.
2. **Needs attention** — only what applies, each one tappable to fix it: untracked days (open
   the list), past plans to confirm, rules near a limit, evidence missing for the year.
3. **Rules** — one card per rule; tap opens that rule.
4. **Next flight** — opens the flight.
5. **This year** — map and countries, in the one country palette.
Add menu (FAB): Log today · Add a flight · Plan a stay · Add evidence · Import.

## Calendar

Segmented Month · Year · List. List is the old Timeline (stays, crossings, gaps with "Fill in").
Every view opens the same day sheet with: countries (confirm/remove), flights (open), **Add a
flight**, evidence (photos to choose, files), **Add evidence**, and clear with confirmation.
Every edit, from any view or sheet, offers Undo.

## Evidence

Year chosen in the top bar. Sections:

1. **Coverage** — per country: days counted, and how many are documented / tracked / by hand /
   assumed (the TEAC tiers: certified presence, presumed days, absences).
2. **Checklist** — period documents worth having for the year: tax residence certificate (when
   resident elsewhere), rental contract or deed, utility bills, work and tax records. Ticked
   when one is kept.
3. **Documents** — grouped Travel · Being there · Home · Work, tax and ties · Other; each opens
   to view, edit or remove. Flight documents show their flight.
4. **Export** — the year's pack (summary page, days, every document), travel history, Form 2555.

Add (FAB): kind (grouped, with how much each proves), the file (any document, picture or
Wallet pass), country, a day or a period, a title and a note. The same file isn't kept twice.

## Evidence kinds

From what Spain's TEAC and courts accept (RG 4045/2020, RG 7199/2020, TSJ Andalucía 2025) and
what Cyprus asks for a certificate (T.D. 126): each kind has a group, a strength (strong,
medium, supporting, ties) and whether it covers one day or a period. See `DocumentKind`.

## Flights

Flights keep their number, booking reference, passenger and seat; imports fill in what a saved
flight lacked. Evidence names its flight (`Document.flightId`).

## Consistency rules

- Years are always chosen from a menu ("2026 ▾", newest first, "All time" where it makes
  sense), never a row of buttons: ten years of use must fit.
- Days between a kept boarding pass into a country and the next kept pass out of it count as
  documented there, whatever else recorded them (`Day.between`).
- One country palette, assigned from all-time days, used everywhere.
- Words: **Untracked** (no country), **Assumed** (filled between two same-country days),
  **Planned**, **Recorded**; **Rules** for limits and residency; **Citizenship** everywhere.
- Every text that names a place in the app links to it.
- Destructive actions confirm or offer Undo, never neither.
