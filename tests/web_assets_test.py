import re
import json
import struct
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "plugins" / "dm7-database" / "web" / "dist"
JAR = ROOT / "plugins" / "dm7-database" / "lib" / "dm7-codex-plugin.jar"
STYLES = ROOT / "plugins" / "dm7-database" / "web" / "src" / "styles.css"
PLUGIN = ROOT / "plugins" / "dm7-database"


def luminance(color):
    channels = [int(color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    channels = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4 for value in channels]
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(foreground, background):
    light, dark = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (light + 0.05) / (dark + 0.05)


class WebAssetsTest(unittest.TestCase):
    def test_marketplace_screenshots_are_referenced_1440x900_pngs(self):
        manifest = json.loads((PLUGIN / ".codex-plugin" / "plugin.json").read_text(encoding="utf-8"))
        screenshots = manifest["interface"]["screenshots"]
        self.assertEqual(screenshots, ["./assets/screenshot-console.png", "./assets/screenshot-release.png"])
        for reference in screenshots:
            image = PLUGIN / reference.removeprefix("./")
            data = image.read_bytes()
            self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(struct.unpack(">II", data[16:24]), (1440, 900))
            self.assertLess(len(data), 2_000_000)

    def test_light_and_dark_tokens_meet_aa_contrast(self):
        styles = STYLES.read_text(encoding="utf-8")
        light = re.search(r":root\s*\{([^}]+)\}", styles, re.S).group(1)
        dark = re.search(r"\[data-theme=\"dark\"\]\s*\{([^}]+)\}", styles, re.S).group(1)
        for theme in (light, dark):
            tokens = dict(re.findall(r"--([a-z-]+):\s*(#[0-9a-fA-F]{6})", theme))
            self.assertGreaterEqual(contrast(tokens["on-accent"], tokens["accent"]), 4.5)
            self.assertGreaterEqual(contrast(tokens["faint"], tokens["surface"]), 4.5)

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
