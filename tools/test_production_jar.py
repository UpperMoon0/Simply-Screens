from pathlib import Path
import tempfile
import unittest
import zipfile

from assert_production_jar import obsolete_entries


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


if __name__ == "__main__": unittest.main()
