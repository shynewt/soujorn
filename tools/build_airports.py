"""Builds the airport list the flight editor searches, from OurAirports (public domain).

    python3 -I tools/build_airports.py <airports.csv> app/src/main/assets/airports.tsv

Keeps airports with an IATA code and scheduled service. One line each:
    IATA <tab> size (2 large, 1 medium, 0 small) <tab> name <tab> city <tab> ISO country
"""

import csv
import sys

SIZES = {"large_airport": 2, "medium_airport": 1, "small_airport": 0}


def main():
    source, target = sys.argv[1], sys.argv[2]
    rows = []
    for row in csv.DictReader(open(source, encoding="utf-8")):
        code = row["iata_code"].strip().upper()
        if len(code) != 3 or row["scheduled_service"] != "yes" or row["type"] not in SIZES:
            continue
        clean = lambda value: " ".join(value.replace("\t", " ").split())
        rows.append((code, SIZES[row["type"]], clean(row["name"]), clean(row["municipality"]), row["iso_country"]))
    # One entry per code; the larger airport wins the rare duplicate.
    best = {}
    for row in rows:
        if row[0] not in best or row[1] > best[row[0]][1]:
            best[row[0]] = row
    with open(target, "w", encoding="utf-8") as out:
        for code in sorted(best):
            out.write("\t".join(str(field) for field in best[code]) + "\n")
    print(f"{len(best)} airports")


main()
