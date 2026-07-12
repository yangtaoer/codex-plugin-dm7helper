#!/usr/bin/env python3
"""Bounded recursive secret scanner for DM7 release inputs and archives."""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import os
from pathlib import Path, PurePosixPath
import sys
import zipfile


SECRET_ENV = ("DM7_IT_JDBC_URL", "DM7_IT_USERNAME", "DM7_IT_PASSWORD", "DM7_IT_DRIVER_JAR")
MAX_DEPTH = 4
MAX_ENTRIES = 10_000
MAX_ENTRY_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200


class ScanFailure(Exception):
    pass


@dataclass
class Budget:
    entries: int = 0
    uncompressed_bytes: int = 0

    def charge(self, size: int) -> None:
        self.entries += 1
        self.uncompressed_bytes += size
        if self.entries > MAX_ENTRIES or size > MAX_ENTRY_BYTES or self.uncompressed_bytes > MAX_TOTAL_BYTES:
            raise ScanFailure("Package archive safety limit exceeded")


def secret_patterns() -> tuple[bytes, ...]:
    patterns: set[bytes] = set()
    for name in SECRET_ENV:
        value = os.environ.get(name)
        if value and value.strip():
            patterns.update((value.encode("utf-8"), value.encode("utf-16-le"), value.encode("utf-16-be")))
    return tuple(patterns)


def assert_secret_free(data: bytes, patterns: tuple[bytes, ...]) -> None:
    if any(pattern in data for pattern in patterns):
        raise ScanFailure("Package contains an integration environment value")


def is_archive(data: bytes) -> bool:
    return zipfile.is_zipfile(BytesIO(data))


def scan_archive(data: bytes, patterns: tuple[bytes, ...], budget: Budget, depth: int) -> None:
    if depth > MAX_DEPTH:
        raise ScanFailure("Package archive safety limit exceeded")
    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            for info in archive.infolist():
                name = PurePosixPath(info.filename.replace("\\", "/"))
                if info.is_dir():
                    continue
                if name.is_absolute() or ".." in name.parts or info.flag_bits & 0x1:
                    raise ScanFailure("Package archive safety limit exceeded")
                budget.charge(info.file_size)
                ratio = info.file_size / max(info.compress_size, 1)
                if ratio > MAX_COMPRESSION_RATIO:
                    raise ScanFailure("Package archive safety limit exceeded")
                with archive.open(info) as source:
                    payload = source.read(MAX_ENTRY_BYTES + 1)
                if len(payload) != info.file_size or len(payload) > MAX_ENTRY_BYTES:
                    raise ScanFailure("Package archive safety limit exceeded")
                assert_secret_free(payload, patterns)
                if is_archive(payload):
                    scan_archive(payload, patterns, budget, depth + 1)
    except (zipfile.BadZipFile, RuntimeError, OSError) as error:
        raise ScanFailure("Package archive safety limit exceeded") from error


def scan_plain(path: Path, patterns: tuple[bytes, ...]) -> None:
    overlap = max((len(pattern) for pattern in patterns), default=1) - 1
    tail = b""
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            assert_secret_free(tail + chunk, patterns)
            tail = (tail + chunk)[-overlap:] if overlap else b""


def scan_root(root: Path, per_archive: bool = False) -> None:
    if not root.is_dir():
        raise ScanFailure("Package scan root is invalid")
    patterns = secret_patterns()
    budget = Budget()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        size = path.stat().st_size
        if per_archive:
            scan_plain(path, patterns)
            if zipfile.is_zipfile(path):
                if size > MAX_ENTRY_BYTES:
                    raise ScanFailure("Package archive safety limit exceeded")
                scan_archive(path.read_bytes(), patterns, Budget(), 1)
        else:
            budget.charge(size)
            if size > MAX_ENTRY_BYTES:
                raise ScanFailure("Package archive safety limit exceeded")
            data = path.read_bytes()
            assert_secret_free(data, patterns)
            if is_archive(data):
                scan_archive(data, patterns, budget, 1)


def main() -> int:
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "--per-archive"):
        print("Usage: verify-package-security.py <root> [--per-archive]", file=sys.stderr)
        return 2
    try:
        scan_root(Path(sys.argv[1]), len(sys.argv) == 3)
    except ScanFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    print("Package security scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
