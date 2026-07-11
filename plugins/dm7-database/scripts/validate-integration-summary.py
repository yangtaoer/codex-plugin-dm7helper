#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

import jsonschema


def main() -> int:
    if len(sys.argv) != 3:
        print("Integration summary validation failed", file=sys.stderr); return 2
    try:
        schema = json.loads(Path(sys.argv[1]).read_text("utf-8"))
        value = json.loads(Path(sys.argv[2]).read_text("utf-8"))
        jsonschema.validate(value, schema)
    except Exception:
        print("Integration summary validation failed", file=sys.stderr); return 1
    print("Integration summary validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
