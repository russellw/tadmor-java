#!/usr/bin/env python3
"""Maintain vendor/, the committed Maven repository the build runs against.

Maven has no lockfile and no integrity hashes of its own, so this tool
supplies them (docs/stack.md, "Supply-chain posture"):

  tools/vendor.py sync    Re-resolve vendor/ from scratch with Maven, online
                          and with strict checksums, by running the build the
                          Makefile runs. Refuse any artifact published less
                          than 7 days ago, then write vendor/lock.txt.
  tools/vendor.py check   Verify vendor/ against vendor/lock.txt, offline:
                          every locked file present with its sha256, nothing
                          else.

sync starts from an empty repository, so artifacts the build no longer uses
drop out, and the change is reviewable as a pom.xml diff plus a lock diff.
If sync fails, the previous vendor/ is put back.

Standard library only.
"""

import datetime
import email.utils
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "vendor"
LOCK = VENDOR / "lock.txt"
CENTRAL = "https://repo.maven.apache.org/maven2/"
COOLDOWN = datetime.timedelta(days=7)

# What Maven leaves in a local repository besides the artifacts themselves.
# None of it is needed offline, and the tracking files carry timestamps that
# would make every sync a diff.
NOISE_NAMES = {"_remote.repositories", "resolver-status.properties"}
NOISE_SUFFIXES = (".lastUpdated", ".part", ".lock")


def mvn_populate():
    """Run the Makefile's build into vendor/, so it holds exactly what that
    build resolves: dependencies, plugins, and the surefire JUnit provider.
    Surefire's provider is resolved only when it discovers tests, so tests
    are discovered but filtered out by a tag none carries: none runs, and no
    database is needed."""
    cmd = [
        str(ROOT / "mvnw"), "-B", "-C", "-Dmaven.repo.local=" + str(VENDOR),
        "package", "-Dgroups=vendor-sync-runs-no-tests", "-Dsurefire.failIfNoSpecifiedTests=false",
    ]
    subprocess.run(cmd, cwd=ROOT, check=True)
    shutil.rmtree(ROOT / "target", ignore_errors=True)


def prune():
    for path in sorted(VENDOR.rglob("*"), reverse=True):
        if path.is_file() and (path.name in NOISE_NAMES or path.name.endswith(NOISE_SUFFIXES)
                               or path.name.startswith("maven-metadata")):
            path.unlink()
        elif path.is_dir() and not any(path.iterdir()):
            path.rmdir()


def artifact_files():
    return sorted(p for p in VENDOR.rglob("*") if p.is_file() and p != LOCK)


def published(pom: Path) -> datetime.datetime:
    url = CENTRAL + pom.relative_to(VENDOR).as_posix()
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return email.utils.parsedate_to_datetime(resp.headers["Last-Modified"])


def check_cooldown():
    now = datetime.datetime.now(datetime.timezone.utc)
    recent = []
    for pom in (p for p in artifact_files() if p.suffix == ".pom"):
        when = published(pom)
        if now - when < COOLDOWN:
            recent.append(f"  {pom.parent.relative_to(VENDOR).as_posix()} published {when:%Y-%m-%d}")
    if recent:
        sys.exit("versions published less than 7 days ago (cooldown):\n" + "\n".join(recent))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_lock():
    lines = [f"{sha256(p)}  {p.relative_to(VENDOR).as_posix()}\n" for p in artifact_files()]
    LOCK.write_text("".join(lines))
    return len(lines)


def sync():
    old = ROOT / "vendor.old"
    if old.exists():
        sys.exit(f"{old} exists from an interrupted sync; restore or remove it first")
    if VENDOR.exists():
        VENDOR.rename(old)
    try:
        VENDOR.mkdir()
        mvn_populate()
        prune()
        check_cooldown()
        n = write_lock()
    except BaseException:
        shutil.rmtree(VENDOR, ignore_errors=True)
        if old.exists():
            old.rename(VENDOR)
        raise
    shutil.rmtree(old, ignore_errors=True)
    print(f"vendor/: {n} files locked in vendor/lock.txt")


def check():
    if not LOCK.exists():
        sys.exit("vendor/lock.txt is missing; run tools/vendor.py sync")
    locked = {}
    for line in LOCK.read_text().splitlines():
        digest, rel = line.split("  ", 1)
        locked[rel] = digest
    present = {p.relative_to(VENDOR).as_posix(): p for p in artifact_files()}
    problems = [f"missing: {rel}" for rel in sorted(locked.keys() - present.keys())]
    problems += [f"not locked: {rel}" for rel in sorted(present.keys() - locked.keys())]
    problems += [f"modified: {rel}" for rel in sorted(locked.keys() & present.keys())
                 if sha256(present[rel]) != locked[rel]]
    if problems:
        sys.exit("vendor/ does not match vendor/lock.txt:\n" + "\n".join("  " + p for p in problems))
    print(f"vendor/: {len(locked)} files match vendor/lock.txt")


def main():
    commands = {"sync": sync, "check": check}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    os.chdir(ROOT)
    commands[sys.argv[1]]()


if __name__ == "__main__":
    main()
