#!/usr/bin/env python3
"""Remove JVM/system-property dumps from Surefire and Failsafe XML reports."""
from __future__ import annotations

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def sanitize(target: Path) -> int:
    count = 0
    for directory in (target / "surefire-reports", target / "failsafe-reports"):
        if not directory.exists():
            continue
        for report in directory.glob("TEST-*.xml"):
            tree = ET.parse(report)
            root = tree.getroot()
            removed = False
            for properties in list(root.findall("properties")):
                root.remove(properties)
                removed = True
            if removed:
                tree.write(report, encoding="utf-8", xml_declaration=True)
                count += 1
    return count


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: sanitize-test-reports.py <maven-target>", file=sys.stderr)
        return 2
    try:
        count = sanitize(Path(sys.argv[1]))
    except (OSError, ET.ParseError):
        print("Test report sanitization failed", file=sys.stderr)
        return 1
    print(f"Test report sanitization passed: {count} reports rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
