#!/usr/bin/env python3
"""Verify the cross-version screen renderer contract for flush image layers.

This is deliberately a source contract rather than a fake unit rendering test: the
three supported Minecraft renderer APIs are different, while the regression is a
small render-state/geometry invariant that must remain consistent across them.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = Path("common/src/main/resources/assets/simply_screens/models/block/screen.json")
OFFSET_RE = re.compile(r"BASE_OFFSET\s*=\s*([0-9.]+)f\s*;")
EXPECTED_OFFSET = 0.501


@dataclass(frozen=True)
class RendererContract:
    name: str
    path: Path
    polygon_offset_call: str
    plain_text_call: str
    fixed_offset_fragments: tuple[str, ...]


FIXED_SWITCH_OFFSET = (
    "case NORTH, SOUTH -> -BASE_OFFSET;",
    "default -> BASE_OFFSET;",
)

RENDERERS = (
    RendererContract(
        "1.20.1",
        Path("common-1.20.1/src/main/java/com/nstut/simplyscreens/client/renderers/ScreenBlockEntityRenderer.java"),
        "RenderType.textPolygonOffset(texture)",
        "RenderType.text(texture)",
        FIXED_SWITCH_OFFSET,
    ),
    RendererContract(
        "1.21.1",
        Path("common-1.21.1/src/main/java/com/nstut/simplyscreens/client/renderers/ScreenBlockEntityRenderer.java"),
        "RenderType.textPolygonOffset(texture)",
        "RenderType.text(texture)",
        FIXED_SWITCH_OFFSET,
    ),
    RendererContract(
        "26.1.2",
        Path("neoforge-26.1.2/src/main/java/com/nstut/simplyscreens/client/renderers/ScreenBlockEntityRenderer.java"),
        "RenderTypes.textPolygonOffset(state.texture)",
        "RenderTypes.text(state.texture)",
        (
            "state.facing == Direction.NORTH || state.facing == Direction.SOUTH ? -BASE_OFFSET : BASE_OFFSET",
        ),
    ),
)


def verify_renderer(root: Path, contract: RendererContract) -> list[str]:
    path = root / contract.path
    if not path.is_file():
        return [f"{contract.name}: missing renderer {contract.path}"]

    text = path.read_text(encoding="utf-8")
    # This remains the cheap source preflight. The compiled gate checks executable
    # call sites after compilation; comments and formatting cannot bless a call.
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    text = re.sub(r"\s+", " ", text)
    errors: list[str] = []

    offset_match = OFFSET_RE.search(text)
    if offset_match is None:
        errors.append(f"{contract.name}: BASE_OFFSET is missing")
    else:
        offset = float(offset_match.group(1))
        if offset != EXPECTED_OFFSET:
            errors.append(
                f"{contract.name}: BASE_OFFSET must remain {EXPECTED_OFFSET:.3f}f, found {offset:.6g}f; "
                "fix depth ordering with render state, not visible geometric separation"
            )

    for fragment in contract.fixed_offset_fragments:
        if fragment not in text:
            errors.append(
                f"{contract.name}: image-plane transform must remain a fixed BASE_OFFSET; "
                "do not scale physical separation with camera distance"
            )
            break

    if contract.polygon_offset_call not in text:
        errors.append(
            f"{contract.name}: screen image quad must use {contract.polygon_offset_call}"
        )
    if contract.plain_text_call in text:
        errors.append(
            f"{contract.name}: plain text render type regresses distance-dependent z-fighting"
        )
    if "textSeeThrough" in text or "text_see_through" in text:
        errors.append(
            f"{contract.name}: see-through text rendering would break normal world occlusion"
        )

    return errors


def verify_screen_model(root: Path) -> list[str]:
    path = root / MODEL_PATH
    if not path.is_file():
        return [f"missing screen model {MODEL_PATH}"]

    try:
        model = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"cannot parse screen model: {exc}"]

    elements = model.get("elements")
    if not isinstance(elements, list):
        return ["screen model must declare explicit full-cube geometry"]

    full_cube = None
    for element in elements:
        if element.get("from") == [0, 0, 0] and element.get("to") == [16, 16, 16]:
            full_cube = element
            break
    if full_cube is None:
        return [
            "screen model geometry changed from the full 0..16 cube; re-evaluate the 0.501 render-plane contract"
        ]

    north = full_cube.get("faces", {}).get("north", {})
    if north.get("texture") != "#front":
        return ["screen model's authored north face must remain the front surface for this contract"]
    return []


def verify(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    errors.extend(verify_screen_model(root))
    for contract in RENDERERS:
        errors.extend(verify_renderer(root, contract))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()

    errors = verify(args.root.resolve())
    if errors:
        print("SIMPLYSCREENS_RENDER_CONTRACT_FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("SIMPLYSCREENS_RENDER_CONTRACT_PASS")
    print(
        "All supported renderers keep the image plane fixed at 0.501 and use vanilla polygon-offset text depth ordering."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
