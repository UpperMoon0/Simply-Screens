"""One version-agnostic manifest drives execution, evidence validation and CI coverage."""
from itertools import product

TARGETS = ("fabric-1.20.1", "forge-1.20.1", "fabric-1.21.1", "neoforge-1.21.1", "neoforge-26.1.2")
FACES = ("NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN")
# Four samples cover every unique sub-degree raster alignment used by the
# framebuffer oracle. Reload cases repeat the same four after the reload.
SAMPLES = 4
NF26_TARGET = "neoforge-26.1.2"
VARIANTS = ("fixed", "plain", "see-through")
NEAR_REFERENCE_FACES = ("NORTH", "SOUTH", "EAST", "WEST")


def sample_count(case):
    return SAMPLES * (2 if case.get("reload") else 1)


def variants(_target):
    """Every supported target runs the same semantic variants."""
    return VARIANTS


def cases(variant="fixed"):
    if variant not in VARIANTS:
        raise ValueError(f"unknown visual variant: {variant}")
    result = []
    # Near reference and far negative controls use identical scenes/settings.
    combinations = product(FACES, (2, 8), (8, 32, 64, 160), (0, 60))
    for face, size, distance, angle in combinations:
        # A 2x2 screen at 160 blocks and 60 degrees is only ~3 pixels wide at the
        # fixed viewport; 30 degrees retains independent interior samples without
        # weakening the pixel threshold.
        if size == 2 and distance == 160 and angle == 60:
            angle = 30
        if variant == "plain" and (size != 8 or distance not in (8, 64, 160)):
            continue
        if variant == "see-through":
            continue
        result.append(dict(id=f"{face}-{size}-{distance}-{angle}", facing=face,
                           size=size, distance=distance, angle=angle, occluded=False))

    # Fixed and see-through use the same six occlusion scenes; see-through is the
    # deliberate negative control proving the oracle detects foreground leakage.
    if variant != "plain":
        for face in FACES:
            result.append(dict(id=f"{face}-occlusion", facing=face, size=8,
                               distance=32, angle=0, occluded=True))

    if variant == "fixed":
        for face in FACES:
            result.append(dict(id=f"{face}-reload", facing=face, size=8,
                               distance=32, angle=0, occluded=False, reload=True))
        # Representative boundary coverage remains part of the common fixed contract.
        for face in ("NORTH", "UP"):
            result.append(dict(id=f"{face}-cross-chunk", facing=face, size=8,
                               distance=32, angle=0, occluded=False, crossChunk=True))
        result.append(dict(id="NORTH-anchor-loaded-fallback", facing="NORTH", size=8,
                           distance=32, angle=0, occluded=False, anchorLoadedFallback=True))
        result.append(dict(id="NORTH-alpha", facing="NORTH", size=8,
                           distance=32, angle=0, occluded=False, alpha=True))
        # Keep the 64x64 fixture last: clearing 4096 real ScreenBlockEntity instances
        # would legitimately schedule thousands of structure refreshes and can trip
        # the dedicated-server watchdog before the next case. At view distance 3,
        # this camera lands in chunk (-7,-1), outside both modern tracked radius and
        # legacy 1.20.1's viewDistance+3 client cache while child chunks remain.
        result.append(dict(id="NORTH-anchor-unloaded", facing="NORTH", size=64,
                           distance=68, angle=75, occluded=False, anchorUnloaded=True, maxPixelDistance=44))
    return result


def expected_outcome(variant, results):
    """Infrastructure failures never count as detected rendering regressions."""
    required = {case["id"] for case in cases(variant)}
    if set(results) != required:
        raise ValueError("missing or extra visual scenarios")
    if any(r.get("status") not in ("pass", "pixel-failure") for r in results.values()):
        raise ValueError("invalid/incomplete visual result")

    if variant == "fixed":
        if any(r["status"] != "pass" for r in results.values()):
            raise ValueError("fixed renderer failed the pixel contract")
    elif variant == "plain":
        near = [
            results[c["id"]]
            for c in cases(variant)
            if c["distance"] == 8 and c["angle"] == 0 and c["facing"] in NEAR_REFERENCE_FACES
        ]
        if any(r["status"] != "pass" for r in near):
            raise ValueError("plain near reference failed; invalid comparison")
        far = [results[c["id"]] for c in cases(variant) if c["distance"] >= 64]
        if not any(r["status"] == "pixel-failure" for r in far):
            raise ValueError("INCONCLUSIVE: plain renderer did not reproduce z-fighting")
    elif variant == "see-through":
        if not all(r["occlusion_errors"] > 0.5 for r in results.values()):
            raise ValueError("occlusion detector did not reject every see-through control")
