#!/usr/bin/env python3
"""Generate deterministic runtime dependency license inventory from local artifacts."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


PLUGIN = Path(__file__).resolve().parents[1]
OUTPUT = PLUGIN / "licenses"
COMPONENTS = OUTPUT / "components"
MAVEN = [
    ("io.modelcontextprotocol.sdk", "mcp-core", "2.0.0", "MIT"),
    ("org.slf4j", "slf4j-api", "2.0.16", "MIT"),
    ("io.projectreactor", "reactor-core", "3.7.0", "Apache-2.0"),
    ("org.reactivestreams", "reactive-streams", "1.0.4", "MIT-0"),
    ("io.modelcontextprotocol.sdk", "mcp-json-jackson2", "2.0.0", "MIT"),
    ("com.networknt", "json-schema-validator", "2.0.0", "Apache-2.0"),
    ("com.ethlo.time", "itu", "1.14.0", "Apache-2.0"),
    ("com.fasterxml.jackson.dataformat", "jackson-dataformat-yaml", "2.18.3", "Apache-2.0"),
    ("org.yaml", "snakeyaml", "2.3", "Apache-2.0"),
    ("com.fasterxml.jackson.core", "jackson-databind", "2.22.1", "Apache-2.0"),
    ("com.fasterxml.jackson.core", "jackson-core", "2.22.1", "Apache-2.0"),
    ("com.fasterxml.jackson.core", "jackson-annotations", "2.22", "Apache-2.0"),
    ("org.xerial", "sqlite-jdbc", "3.53.2.0", "Apache-2.0 AND BSD-3-Clause"),
    ("org.slf4j", "slf4j-simple", "2.0.17", "MIT"),
]
MCP_LICENSE = """MIT License

Copyright (c) 2025 the original author or authors.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""
REACTIVE_LICENSE = """MIT No Attribution

Copyright 2014 Reactive Streams

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value)


def zip_notices(path: Path) -> list[tuple[str, str]]:
    if not path.is_file():
        return []
    with zipfile.ZipFile(path) as archive:
        names = sorted(name for name in archive.namelist()
                       if re.search(r"(^|/)(LICENSE|NOTICE|COPYING)(\.|$)", name, re.I))
        return [(name, archive.read(name).decode("utf-8", "replace").strip()) for name in names]


def write_component(identifier: str, license_id: str, sections: list[tuple[str, str]], provenance: str) -> dict:
    relative = Path("licenses") / "components" / f"{slug(identifier)}.txt"
    content = "\n\n".join(f"===== {label} =====\n{text}" for label, text in sections).rstrip() + "\n"
    (PLUGIN / relative).write_text(content, encoding="utf-8", newline="\n")
    return {"id": identifier, "license": license_id, "licenseFile": relative.as_posix(), "provenance": provenance}


def generate_maven() -> list[dict]:
    repository = Path.home() / ".m2" / "repository"
    apache = (PLUGIN / "LICENSE").read_text("utf-8")
    result = []
    for group, artifact, version, license_id in MAVEN:
        base = repository / Path(*group.split(".")) / artifact / version
        binary = base / f"{artifact}-{version}.jar"
        sources = base / f"{artifact}-{version}-sources.jar"
        sections = zip_notices(binary)
        provenance = "Maven POM metadata and binary artifact META-INF license/notice resources"
        if artifact in ("mcp-core", "mcp-json-jackson2"):
            sections = [("upstream LICENSE", MCP_LICENSE)] + sections
            provenance += "; https://github.com/modelcontextprotocol/java-sdk/blob/main/LICENSE"
        elif artifact == "reactive-streams":
            sections = [("upstream LICENSE", REACTIVE_LICENSE)] + sections
            provenance += "; https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.4/LICENSE"
        elif artifact == "sqlite-jdbc":
            sections = zip_notices(sources)
            provenance = "Maven source artifact META-INF/maven/org.xerial/sqlite-jdbc license resources"
        elif not any("LICENSE" in name.upper() or "COPYING" in name.upper() for name, _ in sections):
            sections.insert(0, ("Apache-2.0", apache))
            provenance += "; Maven POM declared Apache-2.0"
        if not sections:
            raise RuntimeError(f"no license text for {group}:{artifact}:{version}")
        identifier = f"maven:{group}:{artifact}:{version}"
        result.append(write_component(identifier, license_id, sections, provenance))
    sqlite_notice = "SQLite is in the Public Domain.\n\nProvenance: https://www.sqlite.org/copyright.html\n"
    result.append(write_component("embedded:sqlite-engine@3.53.2", "Public-Domain",
                                  [("SQLite public-domain dedication", sqlite_notice)],
                                  "sqlite-jdbc 3.53.2.0 bundled native SQLite; https://www.sqlite.org/copyright.html"))
    return result


def resolve_pnpm() -> tuple[str, dict[str, str]]:
    command = shutil.which("pnpm.cmd") or shutil.which("pnpm")
    environment = os.environ.copy()
    if not command:
        candidates = sorted((Path.home() / ".cache" / "codex-runtimes").glob("*/dependencies/bin/fallback/pnpm.cmd"))
        if not candidates:
            raise RuntimeError("pnpm not found")
        command = str(candidates[-1])
    nodes = sorted((Path.home() / ".cache" / "codex-runtimes").glob("*/dependencies/node/bin/node.exe"))
    if nodes:
        environment["PATH"] = str(nodes[-1].parent) + os.pathsep + environment["PATH"]
    return command, environment


def generate_npm() -> list[dict]:
    pnpm, environment = resolve_pnpm()
    completed = subprocess.run([pnpm, "--dir", str(PLUGIN / "web"), "licenses", "list", "--prod", "--json"],
                               env=environment, capture_output=True, text=True, encoding="utf-8", check=True)
    result = []
    for license_id, packages in json.loads(completed.stdout).items():
        for package in packages:
            root = Path(package["paths"][0])
            files = sorted(path for path in root.iterdir() if path.is_file() and
                           re.match(r"^(LICENSE|NOTICE|COPYING)(\.|$)", path.name, re.I))
            if not files:
                raise RuntimeError(f"no package license file for {package['name']}")
            sections = [(path.name, path.read_text("utf-8", errors="replace")) for path in files]
            for version in package["versions"]:
                identifier = f"npm:{package['name']}@{version}"
                result.append(write_component(identifier, license_id, sections,
                                              "pnpm licenses list --prod and installed package license/notice files"))
    return result


def main() -> None:
    if COMPONENTS.exists():
        for path in COMPONENTS.glob("*.txt"):
            path.unlink()
    COMPONENTS.mkdir(parents=True, exist_ok=True)
    components = sorted(generate_maven() + generate_npm(), key=lambda item: item["id"])
    inventory = {"schemaVersion": 1, "components": components}
    (OUTPUT / "dependencies.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8", newline="\n")
    print(f"Generated {len(components)} runtime dependency license records")


if __name__ == "__main__":
    main()
