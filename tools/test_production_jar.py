from pathlib import Path
import tempfile
import unittest
import zipfile

from assert_production_jar import REQUIRED_PACKETWEAVE, missing_packetweave_entries, obsolete_entries


class ProductionJarTest(unittest.TestCase):
    def test_test_driver_cannot_ship_even_as_nested_class(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory)/"production.jar"
            for entry in ("com/nstut/simplyscreens/testing/visual/VisualClient.class",
                          "com/nstut/simplyscreens/testing/visual/VisualClient$1.class",
                          "simplyscreens.mixins.json"):
                with self.subTest(entry=entry):
                    with zipfile.ZipFile(jar,"w") as archive:
                        archive.writestr(entry, b"fixture")
                    self.assertEqual([entry], obsolete_entries(jar))
            with zipfile.ZipFile(jar,"w") as archive:
                archive.writestr("com/nstut/simplyscreens/SimplyScreens.class", b"fixture")
            self.assertEqual([],obsolete_entries(jar))

    def test_unrelocated_packetweave_classes_cannot_ship(self):
        entries = (
            "com/nstut/packetweave/api/TransferRegistry.class",
            "com/nstut/packetweave/api/ChunkedTransfer$1.class",
            "META-INF/versions/21/com/nstut/packetweave/api/TransferId.class",
        )
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for entry in entries:
                    archive.writestr(entry, b"fixture")
                for entry in REQUIRED_PACKETWEAVE:
                    archive.writestr(entry, b"fixture")
            self.assertEqual(sorted(entries), obsolete_entries(jar))
            self.assertEqual([], missing_packetweave_entries(jar))

    def test_every_private_core_class_and_license_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            for omitted in REQUIRED_PACKETWEAVE:
                with self.subTest(omitted=omitted):
                    with zipfile.ZipFile(jar, "w") as archive:
                        for entry in REQUIRED_PACKETWEAVE - {omitted}:
                            archive.writestr(entry, b"fixture")
                    self.assertEqual([omitted], missing_packetweave_entries(jar))

    def test_original_names_are_not_a_substitute_for_private_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "production.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for entry in REQUIRED_PACKETWEAVE:
                    archive.writestr(entry.replace("simplyscreens/internal/", ""), b"fixture")
            self.assertEqual(8, len(missing_packetweave_entries(jar)))


if __name__ == "__main__": unittest.main()
