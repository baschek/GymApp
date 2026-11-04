"""Builds the region paths of the app's body drawing from MuscleMap's male front/back muscle paths (mm.json)."""
# Input: mm.json with the male front/back paths extracted from npm @musclemap/assets 1.1.0 (MIT). Output: BodyArtPaths.kt.
import json, re, sys

CUT = dict(medial_y=392.0, lateral_y=364.0)  # chest cut: y at the sternum edge and at the arm side

SLUG = {  # (group, id prefix) -> slug
    "TRAPEZIUS": None, "CHEST": None, "SHOULDERS_FRONT": "shoulder_front", "SHOULDERS_SIDE": "shoulder_side",
    "SHOULDERS_REAR": "shoulder_rear", "BICEPS": "biceps", "TRICEPS": "triceps", "FOREARMS": "forearm",
    "OBLIQUES": "obliques", "CORE": "core", "ABDUCTORS": "abductors", "ADDUCTORS": "adductors", "QUADS": "quadriceps",
    "CALVES": "calves", "RHOMBOIDS": "rhomboids", "LATS": "lats", "BACK_LOWER": "lower_back", "GLUTES": "gluteus",
    "HAMSTRINGS": "hamstrings",
}

def nums(d): return [float(x) for x in re.findall(r"-?\d+\.?\d*", d)]

def flatten(d, steps=10):
    toks = re.findall(r"[MLCZ]|-?\d+\.?\d*", d)
    pts, i, cmd, cur = [], 0, None, None
    while i < len(toks):
        t = toks[i]
        if t in "MLCZ": cmd = t; i += 1; 
        if cmd == "Z" or i >= len(toks): continue
        if cmd in "ML":
            cur = (float(toks[i]), float(toks[i+1])); pts.append(cur); i += 2
        elif cmd == "C":
            p = [float(x) for x in toks[i:i+6]]; i += 6
            p0, p1, p2, p3 = cur, (p[0], p[1]), (p[2], p[3]), (p[4], p[5])
            for k in range(1, steps+1):
                s = k/steps; u = 1-s
                pts.append((u**3*p0[0]+3*u*u*s*p1[0]+3*u*s*s*p2[0]+s**3*p3[0], u**3*p0[1]+3*u*u*s*p1[1]+3*u*s*s*p2[1]+s**3*p3[1]))
            cur = p3
    return pts

def clip(poly, a, b, keep_above):
    def side(p):  # >0 = above the line a-b (smaller y), line is never vertical
        t = (p[0]-a[0])/(b[0]-a[0]); ly = a[1]+t*(b[1]-a[1]); return (ly - p[1]) if keep_above else (p[1] - ly)
    out = []
    for i, p in enumerate(poly):
        q = poly[(i+1) % len(poly)]; sp, sq = side(p), side(q)
        if sp >= 0: out.append(p)
        if (sp >= 0) != (sq >= 0):
            r = sp/(sp-sq); out.append((p[0]+r*(q[0]-p[0]), p[1]+r*(q[1]-p[1])))
    return out

def poly_d(poly): return "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in poly) + " Z"

def area(poly): return abs(sum(poly[i][0]*poly[(i+1)%len(poly)][1]-poly[(i+1)%len(poly)][0]*poly[i][1] for i in range(len(poly))))/2

data = json.load(open("mm.json"))
views = {}
for view, ents in data.items():
    regions, outline = {}, None
    for e in ents:
        if e["kind"] == "outline": outline = e["d"]; continue
        g = e["group"]; mid = e["id"] or ""
        if g == "TRAPEZIUS":
            if view == "front": slug = "trapezius_upper"
            else: slug = "trapezius_upper" if (e is ents[1] or e is ents[2]) else "trapezius_lower"
            regions.setdefault(slug, []).append(e["d"])
        elif g == "CHEST":
            poly = flatten(e["d"]); xs = [p[0] for p in poly]
            left = mid.endswith("LEFT")  # viewer's left pec: medial edge at larger x
            x0, x1 = (max(xs), min(xs)) if left else (min(xs), max(xs))
            a, b = (x0, CUT["medial_y"]), (x1, CUT["lateral_y"])
            up, lo = clip(poly, a, b, True), clip(poly, a, b, False)
            print(view, mid, "area", round(area(poly)), "upper", round(area(up)), "lower", round(area(lo)), "sum", round(area(up)+area(lo)))
            regions.setdefault("chest_upper", []).append(poly_d(up)); regions.setdefault("chest_lower", []).append(poly_d(lo))
        else:
            regions.setdefault(SLUG[g], []).append(e["d"])
    views[view] = dict(regions=regions, outline=outline)

# crop box over both outlines
allx, ally = [], []
for v in views.values():
    n = nums(v["outline"]); allx += n[0::2]; ally += n[1::2]
M = 8
cx, cy = min(allx)-M, min(ally)-M
W, H = max(allx)+M-cx, max(ally)+M-cy
print("crop", cx, cy, W, H)

def shift(d):
    def f(m):
        return m.group(0)
    toks = re.findall(r"[MLCZ]|-?\d+\.?\d*", d); out = []; k = 0
    for t in toks:
        if t in "MLCZ": out.append(t); k = 0
        else:
            v = float(t) - (cx if k % 2 == 0 else cy); k += 1
            out.append(f"{v:.1f}".rstrip("0").rstrip(".") if "." in f"{v:.1f}" else f"{v:.1f}")
    s = ""
    for t in out:
        s += t if (t in "MLCZ" or not s or s[-1] in "MLCZ") else " " + t
    return s

res = {"width": round(W), "height": round(H), "views": {}}
for view, v in views.items():
    res["views"][view] = {"outline": shift(v["outline"]), "regions": {k: " ".join(shift(d) for d in ds) for k, ds in v["regions"].items()}}
json.dump(res, open("regions.json", "w"))
print({v: list(res["views"][v]["regions"]) for v in res["views"]})
print({v: {k: len(d) for k, d in res["views"][v]["regions"].items()} for v in res["views"]}, {v: len(res["views"][v]["outline"]) for v in res["views"]})
