import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from assert_production_jar import loader_metadata, obsolete_entries, packetweave_dependency_errors


def metadata(target, minimum="0.1.1", required=True, side="BOTH"):
    if target.startswith("fabric-"):
        return json.dumps({"id": "simply_screens", "environment": "*" if side == "BOTH" else "client",
                           "depends" if required else "suggests": {"packetweave": ">=" + minimum}})
    requirement = (f"mandatory = {str(required).lower()}" if target.startswith("forge-") else
                   f'type = "{"required" if required else "optional"}"')
    return ('[[mods]]\nmodId = "simply_screens"\nversion = "1.0"\n'
            '[[dependencies.simply_screens]]\nmodId = "packetweave"\n'
            f'{requirement}\nversionRange = "[{minimum},)"\nside = "{side}"\n')


class ProductionJarTest(unittest.TestCase):
    def test_test_driver_cannot_ship_even_as_nested_class(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            for entry in ("com/nstut/simplyscreens/testing/visual/VisualClient.class",
                          "com/nstut/simplyscreens/testing/visual/VisualClient$1.class",
                          "simplyscreens.mixins.json"):
                with self.subTest(entry=entry):
                    with zipfile.ZipFile(jar, "w") as archive:
                        archive.writestr(entry, b"fixture")
                    self.assertEqual([entry], obsolete_entries(jar))

    def test_no_packetweave_classes_can_ship_public_private_multirelease_or_nested(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            entries = [prefix + package + "api/TransferRegistry$1.class"
                       for prefix in ("", "META-INF/versions/21/")
                       for package in ("com/nstut/packetweave/", "com/nstut/simplyscreens/internal/packetweave/")]
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as archive:
                archive.writestr(entries[0], b"fixture")
            with zipfile.ZipFile(jar, "w") as archive:
                for entry in entries:
                    archive.writestr(entry, b"fixture")
                archive.writestr("META-INF/jarjar/core.jar", nested.getvalue())
            self.assertEqual(sorted(entries + ["META-INF/jarjar/core.jar!" + entries[0]]), obsolete_entries(jar))

    def test_required_compatible_dependency_on_both_sides_for_each_loader(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            for target in ("fabric-1.20.1", "fabric-1.21.1", "forge-1.20.1", "neoforge-1.21.1", "neoforge-26.1.2"):
                for changes in ({}, {"minimum": "0.1.0"}, {"required": False}, {"side": "CLIENT"}):
                    with self.subTest(target=target, changes=changes):
                        with zipfile.ZipFile(jar, "w") as archive:
                            archive.writestr(loader_metadata(target), metadata(target, **changes))
                        self.assertEqual(bool(changes), bool(packetweave_dependency_errors(jar)))
                        self.assertEqual([], obsolete_entries(jar))

    def test_missing_or_unexpanded_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            for entries in ({}, {"fabric.mod.json": "{}"}, {"fabric.mod.json": metadata("fabric-1.21.1", "${packetweave_version}")}):
                with zipfile.ZipFile(jar, "w") as archive:
                    for name, content in entries.items():
                        archive.writestr(name, content)
                self.assertTrue(packetweave_dependency_errors(jar))


if __name__ == "__main__":
    unittest.main()
