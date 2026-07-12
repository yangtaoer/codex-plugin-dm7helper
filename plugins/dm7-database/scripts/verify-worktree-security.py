#!/usr/bin/env python3
"""Scan every Git-known worktree file, including untracked and ignored files."""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import zipfile

SECRET_ENV=("DM7_IT_JDBC_URL","DM7_IT_USERNAME","DM7_IT_PASSWORD","DM7_IT_DRIVER_JAR")
MAX_DEPTH=4
MAX_ARCHIVE_ENTRIES=10_000
MAX_ARCHIVE_ENTRY_BYTES=64*1024*1024
MAX_ARCHIVE_TOTAL_BYTES=256*1024*1024
MAX_COMPRESSION_RATIO=200

class ScanFailure(Exception): pass

@dataclass
class ArchiveBudget:
    entries:int=0
    total:int=0
    def charge(self,size:int)->None:
        self.entries+=1;self.total+=size
        if self.entries>MAX_ARCHIVE_ENTRIES or size>MAX_ARCHIVE_ENTRY_BYTES or self.total>MAX_ARCHIVE_TOTAL_BYTES:
            raise ScanFailure("Worktree archive safety limit exceeded")

def patterns()->tuple[bytes,...]:
    values:set[bytes]=set()
    for name in SECRET_ENV:
        value=os.environ.get(name)
        if value and value.strip(): values.update((value.encode(),value.encode("utf-16-le"),value.encode("utf-16-be")))
    return tuple(values)

def reject(data:bytes,secrets:tuple[bytes,...])->None:
    if any(secret in data for secret in secrets): raise ScanFailure("Worktree contains an integration environment value")

def scan_archive(data:bytes,secrets:tuple[bytes,...],budget:ArchiveBudget,depth:int)->None:
    if depth>MAX_DEPTH: raise ScanFailure("Worktree archive safety limit exceeded")
    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            for info in archive.infolist():
                name=PurePosixPath(info.filename.replace("\\","/"))
                if info.is_dir(): continue
                if name.is_absolute() or ".." in name.parts or info.flag_bits&1: raise ScanFailure("Worktree archive safety limit exceeded")
                budget.charge(info.file_size)
                if info.file_size/max(info.compress_size,1)>MAX_COMPRESSION_RATIO: raise ScanFailure("Worktree archive safety limit exceeded")
                with archive.open(info) as source: payload=source.read(MAX_ARCHIVE_ENTRY_BYTES+1)
                if len(payload)!=info.file_size or len(payload)>MAX_ARCHIVE_ENTRY_BYTES: raise ScanFailure("Worktree archive safety limit exceeded")
                reject(payload,secrets)
                if zipfile.is_zipfile(BytesIO(payload)): scan_archive(payload,secrets,budget,depth+1)
    except (zipfile.BadZipFile,RuntimeError,OSError) as error: raise ScanFailure("Worktree archive safety limit exceeded") from error

def scan_plain(path:Path,secrets:tuple[bytes,...])->None:
    overlap=max((len(value) for value in secrets),default=1)-1;tail=b""
    with path.open("rb") as source:
        while chunk:=source.read(1024*1024):
            reject(tail+chunk,secrets);tail=(tail+chunk)[-overlap:] if overlap else b""

def listed_paths(root:Path)->list[Path]:
    # Git's --ignored mode filters --others to ignored paths, so enumerate the
    # ordinary tracked/untracked set too.  The first invocation intentionally
    # remains the audited command required for ignored-file coverage.
    commands=(
        ["git","-C",str(root),"ls-files","--cached","--others","--ignored","--exclude-standard","-z"],
        ["git","-C",str(root),"ls-files","--cached","--others","--exclude-standard","-z"],
    )
    output=b"".join(subprocess.run(command,capture_output=True,check=True).stdout for command in commands)
    paths=[];seen:set[Path]=set()
    for raw in output.split(b"\0"):
        if not raw: continue
        relative=Path(os.fsdecode(raw));
        if relative.parts and relative.parts[0]==".git": continue
        # Keep validation lexical: resolving here would follow a worktree
        # symlink/junction before the scanner can inspect the link itself.
        candidate=Path(os.path.abspath(root/relative))
        try: candidate.relative_to(root)
        except ValueError: raise ScanFailure("Worktree path escaped repository")
        path=root/relative
        if path not in seen: paths.append(path);seen.add(path)
    return paths

def scan(root:Path)->int:
    root=root.resolve();secrets=patterns();count=0
    for path in listed_paths(root):
        if path.is_symlink(): reject(os.fsencode(os.readlink(path)),secrets);count+=1;continue
        if not path.is_file(): continue
        count+=1;scan_plain(path,secrets)
        if zipfile.is_zipfile(path):
            if path.stat().st_size>MAX_ARCHIVE_ENTRY_BYTES:
                raise ScanFailure("Worktree archive safety limit exceeded")
            scan_archive(path.read_bytes(),secrets,ArchiveBudget(),1)
    return count

def main()->int:
    if len(sys.argv)!=2: print("Usage: verify-worktree-security.py <repository>",file=sys.stderr);return 2
    try: count=scan(Path(sys.argv[1]))
    except (ScanFailure,subprocess.CalledProcessError,OSError) as error:
        print(str(error) if isinstance(error,ScanFailure) else "Worktree security scan failed",file=sys.stderr);return 1
    print(f"Worktree security scan passed: {count} files")
    return 0

if __name__=="__main__": raise SystemExit(main())
