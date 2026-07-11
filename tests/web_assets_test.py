import re
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "plugins" / "dm7-database" / "web" / "dist"
JAR = ROOT / "plugins" / "dm7-database" / "lib" / "dm7-codex-plugin.jar"


class WebAssetsTest(unittest.TestCase):
    def test_index_uses_console_scoped_asset_urls(self):
        index = (DIST / "index.html").read_text(encoding="utf-8")
        references = re.findall(r'(?:src|href)="([^"]+)"', index)
        self.assertTrue(references)
        self.assertTrue(all(value.startswith("/app/") for value in references), references)

    def test_distribution_and_fat_jar_are_production_safe(self):
        self.assertFalse(list(DIST.rglob("*.map")))
        with zipfile.ZipFile(JAR) as archive:
            names = archive.namelist()
            self.assertIn("web/index.html", names)
            self.assertTrue(any(name.startswith("web/assets/") and name.endswith(".js") for name in names))
            self.assertFalse(any(name.endswith(".map") for name in names))
            self.assertFalse(any(name.startswith("static/") for name in names))


if __name__ == "__main__":
    unittest.main()
