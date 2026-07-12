from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"


class PluginLayoutTest(unittest.TestCase):
    def test_required_plugin_files_and_names_match(self):
        manifest = json.loads((PLUGIN / ".codex-plugin" / "plugin.json").read_text("utf-8"))
        market = json.loads((ROOT / ".agents" / "plugins" / "marketplace.json").read_text("utf-8"))
        mcp = json.loads((PLUGIN / ".mcp.json").read_text("utf-8"))
        package = json.loads((PLUGIN / "web" / "package.json").read_text("utf-8"))
        self.assertEqual("dm7-database", manifest["name"])
        self.assertRegex(manifest["version"], r"^0\.1\.0(?:\+codex\.[0-9A-Za-z.-]+)?$")
        self.assertEqual("./.mcp.json", manifest["mcpServers"])
        self.assertEqual("java", mcp["mcpServers"]["dm7"]["command"])
        self.assertEqual("dm7-database", market["plugins"][0]["name"])
        self.assertEqual("./plugins/dm7-database", market["plugins"][0]["source"]["path"])
        self.assertEqual("AVAILABLE", market["plugins"][0]["policy"]["installation"])
        self.assertEqual("ON_INSTALL", market["plugins"][0]["policy"]["authentication"])
        self.assertEqual(manifest["interface"]["category"], market["plugins"][0]["category"])
        self.assertEqual("pnpm@11.7.0", package["packageManager"])
        self.assertTrue((PLUGIN / "web" / "pnpm-lock.yaml").is_file())
        for relative in [".mcp.json", "skills/dm7-database/SKILL.md", "assets/icon.svg", "server/pom.xml", "web/package.json"]:
            self.assertTrue((PLUGIN / relative).is_file(), relative)
        for field in ["composerIcon", "logo", "logoDark"]:
            self.assertTrue((PLUGIN / manifest["interface"][field]).is_file(), field)


if __name__ == "__main__":
    unittest.main()
