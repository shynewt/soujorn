"""Builds the two border assets the app ships with, from Natural Earth admin-0 GeoJSON.

    python3 -I tools/build_geodata.py <ne_50m_admin_0_countries.geojson> <app/src/main/assets>

borders.bin  - 1:50m borders at 1e-4 degree precision, used offline to turn a fix into a country.
world.bin    - the same borders simplified to ~0.15 degree, used to draw the world map.

Both share one layout (big endian, as java.io.DataInputStream reads it):
    "SJGB" u8 version  u8 scaleExponent  u16 countryCount
    per country: 2 ascii bytes code, i32 labelLon, i32 labelLat, u16 polygonCount
      per polygon: u16 ringCount; per ring: varint pointCount, then zigzag-varint deltas of lon,lat
Countries are ordered smallest first, so enclaves (San Marino, Lesotho) match before their host.
"""

import json
import math
import struct
import sys

# Natural Earth leaves a few de facto areas without a code; fold them into the state
# that issues entry stamps there, or drop them when that is ambiguous.
CODE_FIXES = {"Somaliland": "SO", "N. Cyprus": "CY"}
DROP = {"Siachen Glacier"}

# At 1:50m the Vatican sits about 1.5 km west of where it is; this traces the real walls.
OVERRIDES = {
    "VA": [[[(12.4457, 41.9032), (12.4486, 41.9070), (12.4560, 41.9075), (12.4584, 41.9017), (12.4532, 41.9002), (12.4467, 41.9006)]]],
}


def varint(out, value):
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def ring_area(ring):
    return abs(sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]))) / 2


def simplify(points, tolerance):
    """Douglas-Peucker, iterative so long coastlines do not hit the recursion limit."""
    if len(points) < 4:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        (x0, y0), (x1, y1) = points[start], points[end]
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        best, index = 0.0, -1
        for i in range(start + 1, end):
            x, y = points[i]
            # A closed ring starts and ends on the same point, where the distance to the
            # chord degenerates; measure from that point instead.
            if length == 0:
                distance = math.hypot(x - x0, y - y0)
            else:
                distance = abs(dy * x - dx * y + x1 * y0 - y1 * x0) / length
            if distance > best:
                best, index = distance, i
        if best > tolerance:
            keep[index] = True
            stack += [(start, index), (index, end)]
    return [p for p, k in zip(points, keep) if k]


def load(path):
    countries = {}
    for feature in json.load(open(path))["features"]:
        props = feature["properties"]
        name = props["NAME"]
        if name in DROP:
            continue
        code = CODE_FIXES.get(name, props["ISO_A2_EH"])
        if not code or code == "-99":
            raise SystemExit(f"no code for {name}")
        geometry = feature["geometry"]
        polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
        entry = countries.setdefault(code, {"polygons": [], "label": None})
        entry["polygons"] += [[[tuple(p[:2]) for p in ring[:-1]] for ring in polygon] for polygon in polygons]
        # The first feature for a code is the state proper; folded-in areas keep its label.
        if entry["label"] is None:
            entry["label"] = (props["LABEL_X"], props["LABEL_Y"])
    for code, polygons in OVERRIDES.items():
        countries[code]["polygons"] = polygons
        xs = [x for x, _ in polygons[0][0]]
        ys = [y for _, y in polygons[0][0]]
        countries[code]["label"] = (sum(xs) / len(xs), sum(ys) / len(ys))
    return countries


def encode(countries, exponent, tolerance=None, skip=()):
    scale = 10**exponent
    rows = []
    for code, entry in countries.items():
        if code in skip:
            continue
        polygons = entry["polygons"]
        if tolerance:
            largest = max(ring_area(p[0]) for p in polygons)
            kept = []
            for polygon in polygons:
                outer = polygon[0]
                # Specks smaller than the simplification step only add noise to the map,
                # unless they are all the country has (Malta, Singapore, Tuvalu).
                if ring_area(outer) < tolerance * tolerance and ring_area(outer) < largest:
                    continue
                rings = [simplify(r + r[:1], tolerance)[:-1] for r in polygon]
                rings = [r if len(r) >= 3 else polygon[i] for i, r in enumerate(rings) if i == 0 or len(r) >= 3]
                kept.append(rings)
            polygons = kept
        area = sum(ring_area(p[0]) for p in polygons)
        rows.append((area, code, entry["label"], polygons))
    rows.sort(key=lambda row: row[0])

    out = bytearray(b"SJGB")
    out += struct.pack(">BBH", 1, exponent, len(rows))
    points = 0
    for _, code, (lx, ly), polygons in rows:
        out += code.encode("ascii")
        out += struct.pack(">iiH", round(lx * scale), round(ly * scale), len(polygons))
        for polygon in polygons:
            out += struct.pack(">H", len(polygon))
            for ring in polygon:
                quantized = []
                for x, y in ring:
                    q = (round(x * scale), round(y * scale))
                    if not quantized or q != quantized[-1]:
                        quantized.append(q)
                varint(out, len(quantized))
                px = py = 0
                for x, y in quantized:
                    varint(out, zigzag(x - px))
                    varint(out, zigzag(y - py))
                    px, py = x, y
                points += len(quantized)
    return bytes(out), len(rows), points


def main():
    source, assets = sys.argv[1], sys.argv[2]
    countries = load(source)
    for name, data in (
        ("borders.bin", encode(countries, 4)),
        ("world.bin", encode(countries, 2, tolerance=0.15, skip={"AQ"})),
    ):
        blob, count, points = data
        open(f"{assets}/{name}", "wb").write(blob)
        print(f"{name}: {count} countries, {points} points, {len(blob) // 1024} KiB")


main()
