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
                          else, and dependencies.json listing exactly the
                          vendored jars.
  tools/vendor.py manifest
                          Write dependencies.json, the dependency manifest
                          tadmor's tools/measure.py reads (tadmor's
                          docs/counterpart-metrics.md): every vendored jar,
                          its category, and the Maven Central namespace that
                          can publish it. Online: it resolves scopes with
                          maven-dependency-plugin in a throwaway copy of
                          vendor/, which it leaves unchanged. sync runs it.

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
import json
import sys
import tempfile
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
    manifest()


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
    problems += check_manifest()
    if problems:
        sys.exit("vendor/ does not match vendor/lock.txt:\n" + "\n".join("  " + p for p in problems))
    print(f"vendor/: {len(locked)} files match vendor/lock.txt")


MANIFEST = ROOT / "dependencies.json"

# Top-level domains, for telling a reversed-domain groupId (org.apache.tomcat)
# from one without a domain (jakarta.mail, commons-io).
TLDS = {"com", "org", "net", "io", "dev", "app", "ai", "co", "info", "biz", "me", "eu", "ch", "de", "uk", "fr",
        "nl", "be", "at", "es", "it", "se", "no", "fi", "dk", "pl", "cz", "ru", "jp", "cn", "br", "au", "ca", "us",
        "tools", "xyz", "cloud", "page", "one", "software", "systems", "technology"}
# GroupIds from before Central verified namespaces, and their owners. The
# commons-* groups are Apache Commons, published by the ASF.
LEGACY = {"commons-io": "org.apache", "commons-logging": "org.apache", "commons-codec": "org.apache",
          "commons-lang": "org.apache", "commons-collections": "org.apache", "commons-cli": "org.apache"}


def namespace(group):
    """The Maven Central namespace that can publish under a groupId. Central
    grants publishing per verified namespace: a reversed domain (two parts,
    or three for a code host's user namespace such as io.github.user), or,
    for groupIds without a domain, the first part. Central publishes no
    account list, so the namespace is the identity, and an organization
    counts once per namespace it holds."""
    if group in LEGACY:
        return LEGACY[group]
    parts = group.split(".")
    if len(parts) >= 3 and parts[0] in ("io", "com") and parts[1] in ("github", "gitlab", "bitbucket"):
        return ".".join(parts[:3])
    if parts[0] in TLDS and len(parts) >= 2:
        return ".".join(parts[:2])
    return parts[0]


def vendored_jars():
    """(groupId, artifactId, version) -> path, for every jar in vendor/."""
    jars = {}
    for p in artifact_files():
        if p.suffix != ".jar" or p.name.endswith(("-sources.jar", "-javadoc.jar")):
            continue
        rel = p.relative_to(VENDOR).parts
        group, artifact, version = ".".join(rel[:-3]), rel[-3], rel[-2]
        jars[(group, artifact, version)] = p
    return jars


def scopes():
    """(groupId, artifactId, version) -> Maven scope for the project's
    dependencies, test scope included, as maven-dependency-plugin lists them.
    It runs against a throwaway copy of vendor/, since the plugin itself is
    not part of the build and so is not vendored."""
    with tempfile.TemporaryDirectory(prefix="tadmor-manifest-") as tmp:
        repo, out = Path(tmp, "repo"), Path(tmp, "deps.txt")
        shutil.copytree(VENDOR, repo)
        subprocess.run([str(ROOT / "mvnw"), "-B", "-q", f"-Dmaven.repo.local={repo}", "dependency:list",
                        "-DoutputScope=true", f"-DoutputFile={out}", "-DappendOutput=false"], cwd=ROOT, check=True)
        found = {}
        for line in out.read_text().splitlines():
            coords = line.strip().split(" ")[0].split(":")
            if len(coords) in (5, 6):
                group, artifact, version, scope = coords[0], coords[1], coords[-2], coords[-1]
                found[(group, artifact, version)] = scope
        return found


def categorized():
    """Category per vendored jar: runtime for compile and runtime scope, test
    for test scope, and build for the rest, which are Maven's plugins and
    their trees."""
    listed = scopes()
    return {key: ("test" if listed.get(key) == "test" else "runtime" if key in listed else "build")
            for key in vendored_jars()}


def manifest():
    cats = categorized()
    jars = vendored_jars()
    packages = [{
        "ecosystem": "maven", "name": f"{g}:{a}", "version": v, "category": cats[(g, a, v)],
        "identities": [f"maven:{namespace(g)}"], "evidence": f"Maven Central namespace of groupId {g}",
    } for (g, a, v) in sorted(cats, key=lambda k: (cats[k], k))]
    sources = [{"label": f"Maven jars ({c})",
                "bytes": sum(jars[k].stat().st_size for k in cats if cats[k] == c), "lines": None}
               for c in ("runtime", "build", "test")]
    doc = {
        "format": "tadmor-dependencies/1",
        "generator": "tools/vendor.py manifest (tadmor-java)",
        "platform": "linux/x64",
        "toolchains": ["OpenJDK 25 (the operating system's build)", "Apache Maven (via the Maven Wrapper)"],
        "packages": packages,
        "sources": sources,
    }
    MANIFEST.write_text(json.dumps(doc, indent=1) + "\n")
    print(f"wrote {MANIFEST.relative_to(ROOT)}: {len(packages)} packages")


def check_manifest():
    """Problems with dependencies.json, checked offline: it must list
    exactly the vendored jars."""
    if not MANIFEST.exists():
        return ["dependencies.json is missing; run tools/vendor.py manifest"]
    listed = {(p["name"], p["version"]) for p in json.loads(MANIFEST.read_text())["packages"]}
    if listed != {(f"{g}:{a}", v) for g, a, v in vendored_jars()}:
        return ["dependencies.json does not list the vendored jars; run tools/vendor.py manifest"]
    return []


def main():
    commands = {"sync": sync, "check": check, "manifest": manifest}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    os.chdir(ROOT)
    commands[sys.argv[1]]()


if __name__ == "__main__":
    main()
