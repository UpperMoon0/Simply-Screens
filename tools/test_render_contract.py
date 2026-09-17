from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_contract


class RenderContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self._write_valid_tree()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write(self, relative: Path, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _write_valid_tree(self) -> None:
        model = {
            "textures": {"front": "simply_screens:block/screen_front"},
            "elements": [
                {
                    "from": [0, 0, 0],
                    "to": [16, 16, 16],
                    "faces": {"north": {"texture": "#front"}},
                }
            ],
        }
        self._write(render_contract.MODEL_PATH, json.dumps(model))
        for contract in render_contract.RENDERERS:
            fixed_offset = "\n".join(contract.fixed_offset_fragments)
            self._write(
                contract.path,
                "public final class ScreenBlockEntityRenderer {\n"
                "  private static final float BASE_OFFSET = 0.501f;\n"
                f"  {fixed_offset}\n"
                f"  void draw() {{ Object type = {contract.polygon_offset_call}; }}\n"
                "}\n",
            )

    def test_valid_contract_passes(self) -> None:
        self.assertEqual([], render_contract.verify(self.root))

    def test_every_renderer_rejects_each_mutation(self) -> None:
        for contract in render_contract.RENDERERS:
            for mutation in ("plain", "see-through", "large", "commented"):
                with self.subTest(version=contract.name, mutation=mutation):
                    self._write_valid_tree()
                    path = self.root / contract.path
                    text = path.read_text()
                    if mutation == "large":
                        text = text.replace("0.501f", "0.550f")
                    else:
                        replacement = {"plain": contract.plain_text_call,
                                       "see-through": contract.polygon_offset_call.replace("textPolygonOffset", "textSeeThrough"),
                                       "commented": "/* " + contract.polygon_offset_call + " */ null"}[mutation]
                        text = text.replace(contract.polygon_offset_call, replacement)
                    path.write_text(text)
                    self.assertTrue(render_contract.verify(self.root))

    def test_plain_text_render_type_is_rejected(self) -> None:
        contract = render_contract.RENDERERS[0]
        path = self.root / contract.path
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                contract.polygon_offset_call, contract.plain_text_call
            ),
            encoding="utf-8",
        )
        errors = render_contract.verify(self.root)
        self.assertTrue(any("z-fighting" in error for error in errors), errors)

    def test_large_geometric_offset_is_rejected(self) -> None:
        contract = render_contract.RENDERERS[1]
        path = self.root / contract.path
        path.write_text(
            path.read_text(encoding="utf-8").replace("0.501f", "0.550f"),
            encoding="utf-8",
        )
        errors = render_contract.verify(self.root)
        self.assertTrue(any("render state" in error for error in errors), errors)

    def test_camera_dependent_physical_offset_is_rejected(self) -> None:
        contract = render_contract.RENDERERS[0]
        path = self.root / contract.path
        original = contract.fixed_offset_fragments[-1]
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                original, "default -> BASE_OFFSET + cameraDistance * 0.001f;"
            ),
            encoding="utf-8",
        )
        errors = render_contract.verify(self.root)
        self.assertTrue(any("camera distance" in error for error in errors), errors)

    def test_see_through_rendering_is_rejected(self) -> None:
        contract = render_contract.RENDERERS[2]
        path = self.root / contract.path
        path.write_text(path.read_text(encoding="utf-8") + "\nRenderTypes.textSeeThrough(texture);\n", encoding="utf-8")
        errors = render_contract.verify(self.root)
        self.assertTrue(any("occlusion" in error for error in errors), errors)

    def test_model_geometry_change_forces_contract_review(self) -> None:
        path = self.root / render_contract.MODEL_PATH
        model = json.loads(path.read_text(encoding="utf-8"))
        model["elements"][0]["to"] = [16, 16, 15]
        path.write_text(json.dumps(model), encoding="utf-8")
        errors = render_contract.verify(self.root)
        self.assertTrue(any("full 0..16 cube" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
