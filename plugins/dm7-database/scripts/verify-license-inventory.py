#!/usr/bin/env python3
"""Validate that dependency license metadata exactly owns the packaged license files."""
from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
import re
import sys


class InventoryFailure(Exception):
    pass


def validate(plugin: Path) -> list[str]:
    inventory_path = plugin / "licenses" / "dependencies.json"
    try:
        inventory = json.loads(inventory_path.read_text("utf-8"))
    except (OSError, ValueError) as error:
        raise InventoryFailure from error
    components = inventory.get("components")
    refs = inventory.get("licenseRefs", {})
    if not isinstance(components, list) or not isinstance(refs, dict):
        raise InventoryFailure
    identifiers: set[str] = set()
    paths: set[str] = set()
    for item in components:
        if not isinstance(item, dict):
            raise InventoryFailure
        identifier = item.get("id"); relative = item.get("licenseFile")
        license_id = item.get("license"); provenance = item.get("provenance")
        if not all(isinstance(value, str) and value.strip() for value in (identifier, relative, license_id, provenance)):
            raise InventoryFailure
        if identifier in identifiers or relative in paths or "\\" in relative:
            raise InventoryFailure
        identifiers.add(identifier); paths.add(relative)
        pure = PurePosixPath(relative)
        if pure.is_absolute() or ".." in pure.parts or pure.as_posix() != relative or len(pure.parts) != 3 or pure.parts[:2] != ("licenses", "components"):
            raise InventoryFailure
        target = plugin.joinpath(*pure.parts)
        if not target.is_file():
            raise InventoryFailure
        for custom in re.findall(r"LicenseRef-[A-Za-z0-9.-]+", license_id):
            definition = refs.get(custom)
            if not isinstance(definition, dict) or not definition.get("description") or not definition.get("url"):
                raise InventoryFailure
    expected = {"licenses/dependencies.json", *paths}
    actual = {path.relative_to(plugin).as_posix() for path in (plugin / "licenses").rglob("*") if path.is_file()}
    if actual != expected:
        raise InventoryFailure
    return sorted(expected)


def main() -> int:
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "--list"):
        print("Usage: verify-license-inventory.py <plugin-root> [--list]", file=sys.stderr); return 2
    try:
        paths = validate(Path(sys.argv[1]))
    except InventoryFailure:
        print("Dependency license inventory validation failed", file=sys.stderr); return 1
    if len(sys.argv) == 3:
        print("\n".join(paths))
    else:
        print("Dependency license inventory validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
