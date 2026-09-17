"""One manifest drives execution, evidence validation and CI matrix coverage."""
from itertools import product

TARGETS = ("fabric-1.20.1", "forge-1.20.1", "fabric-1.21.1", "neoforge-1.21.1", "neoforge-26.1.2")
FACES = ("NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN")
# Four samples cover every unique sub-degree raster alignment used by the
# framebuffer oracle. Reload cases repeat the same four after the reload.
SAMPLES = 4
NF26_TARGET = "neoforge-26.1.2"
PLAIN_COMPARISONS = ("plain", "original", "single-plain", "tiled-offset")


def sample_count(case):
    return SAMPLES * (2 if case.get("reload") else 1)


def variants(target):
    if target == NF26_TARGET:
        return ("fixed", "original", "single-plain", "tiled-offset", "see-through")
    return ("fixed", "plain", "see-through")


def cases(variant="fixed"):
    result = []
    # Near reference and far negative controls use identical scenes/settings.
    combinations = product(FACES, (2, 8), (8, 32, 64, 160), (0, 60))
    for face, size, distance, angle in combinations:
        # A 2x2 screen at 160 blocks and 60 degrees is only ~3 pixels wide at the
        # fixed 960x720 viewport; after silhouette-edge erosion it has no independent
        # interior samples. Keep the far oblique coverage, but use 30 degrees so the
        # oracle retains a meaningful interior without lowering its sample floor.
        if size == 2 and distance == 160 and angle == 60:
            angle = 30
        if variant in PLAIN_COMPARISONS and (size != 8 or distance not in (8, 64, 160)):
            continue
        if variant == "see-through":
            continue
        result.append(dict(id=f"{face}-{size}-{distance}-{angle}", facing=face,
                           size=size, distance=distance, angle=angle, occluded=False))
    if variant not in PLAIN_COMPARISONS:
        for face in FACES:
            result.append(dict(id=f"{face}-occlusion", facing=face, size=8,
                               distance=32, angle=0, occluded=True))
    if variant == "fixed":
        for face in FACES:
            result.append(dict(id=f"{face}-reload", facing=face, size=8,
                               distance=32, angle=0, occluded=False, reload=True))
    return result


def expected_outcome(variant, results):
    """An infrastructure failure never counts as a detected rendering regression."""
    required = {case["id"] for case in cases(variant)}
    if set(results) != required:
        raise ValueError("missing or extra visual scenarios")
    if any(r.get("status") not in ("pass", "pixel-failure") for r in results.values()):
        raise ValueError("invalid/incomplete visual result")
    if variant == "fixed":
        if any(r["status"] != "pass" for r in results.values()):
            raise ValueError("fixed renderer failed the pixel contract")
    elif variant in PLAIN_COMPARISONS:
        near = [r for c in cases(variant) if c["distance"] == 8 for r in [results[c["id"]]]]
        if any(r["status"] != "pass" for r in near):
            raise ValueError(f"{variant} near reference failed; invalid comparison")
        far = [r for c in cases(variant) if c["distance"] >= 64 for r in [results[c["id"]]]]
        if variant in ("plain", "original") and not any(r["status"] == "pixel-failure" for r in far):
            raise ValueError("INCONCLUSIVE: original/plain renderer did not reproduce z-fighting")
    elif variant == "see-through":
        if not all(r["occlusion_errors"] > 0.5 for r in results.values()):
            raise ValueError("occlusion detector did not reject every see-through control")
    else:
        raise ValueError("unknown variant")


def classify_nf26(variant_results):
    """Classify the 2x2 NeoForge 26 experiment without assuming the root cause."""
    required = {"fixed", "original", "single-plain", "tiled-offset"}
    if not required.issubset(variant_results):
        raise ValueError("missing NeoForge 26 isolation variants")

    def far_failed(name):
        return any(variant_results[name][c["id"]]["status"] == "pixel-failure"
                   for c in cases(name) if c["distance"] >= 64)

    if far_failed("original") is False:
        raise ValueError("INCONCLUSIVE: original NeoForge 26 renderer did not reproduce z-fighting")
    single_plain_fails = far_failed("single-plain")
    tiled_offset_fails = far_failed("tiled-offset")
    if single_plain_fails and not tiled_offset_fails:
        return "depth-state: polygon offset alone fixes the original tiled renderer"
    if not single_plain_fails and tiled_offset_fails:
        return "topology: single-quad ownership alone fixes the plain renderer"
    if single_plain_fails and tiled_offset_fails:
        return "combined: neither polygon offset nor single-quad topology is sufficient alone"
    return "non-unique: either polygon offset or single-quad topology independently removes the corruption"
