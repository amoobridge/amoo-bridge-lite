#!/usr/bin/env python3
"""Scan repository files for secrets and machine-specific identifiers.

Design goals:
  * stdlib only, runs on GitHub Actions and on a developer machine
  * no absolute paths of its own, so the script itself is safe to publish
  * fails closed: any finding exits with code 1

Usage:
    python3 scripts/check-secrets.py              # files a commit would contain
    python3 scripts/check-secrets.py --all        # include gitignored files too
    python3 scripts/check-secrets.py --verbose    # also print scanned file count

Exit codes: 0 = clean, 1 = findings, 2 = cannot enumerate files.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Files that legitimately contain the patterns below (this scanner, and the
# docs that describe them). Matched against the repo-relative POSIX path.
SKIP_PATHS = {
    "scripts/check-secrets.py",
}

# Whole directories that are never interesting even in --all mode.
SKIP_DIRS = {
    ".git", ".idea", "build", "dist", "node_modules", "_tmp",
}

# Artifacts and machine-local files that must never be committed, regardless
# of content. Android signing material is the one that actually bites.
BLOCKED_NAMES = {
    "local.properties": "Android SDK path, machine specific",
    "token.txt": "exported runtime token",
    "status.json": "runtime status, contains http_port",
    "config.json": "runtime config, may carry token and exportToken",
}
BLOCKED_SUFFIXES = {
    ".jks": "Java keystore",
    ".keystore": "Android keystore",
    ".p12": "PKCS#12 keystore",
    ".pfx": "PKCS#12 keystore",
    ".apk": "built artifact, ship it through Releases",
}

MAX_BYTES = 2 * 1024 * 1024
TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".py", ".sh", ".bash", ".ps1", ".bat", ".cmd",
    ".md", ".txt", ".yml", ".yaml", ".json", ".xml", ".properties", ".pro",
    ".gradle", ".cfg", ".ini", ".toml", ".js", ".ts", ".mjs", ".cjs", ".html",
    ".css", ".env", "",
}

# (rule name, compiled regex)
RULES: list[tuple[str, re.Pattern[str]]] = [
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("llm-api-key", re.compile(r"\bsk-[A-Za-z0-9_\-]{16,}\b")),
    ("api-key-assign", re.compile(
        r"\b(?:DEEPSEEK|OPENAI|ANTHROPIC|GEMINI|MOONSHOT|DASHSCOPE)_API_KEY\s*[=:]\s*[\"']?[A-Za-z0-9_\-]{8,}")),
    ("launch-token", re.compile(r"[?&]token=[A-Za-z0-9_\-\.]{16,}")),
    ("aws-access-key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("github-token", re.compile(r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}\b")),
    ("slack-token", re.compile(r"\bxox[abposr]-[A-Za-z0-9\-]{10,}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b")),
    # Device serial: only flag tokens that sit next to adb/serial usage, so
    # ordinary uppercase identifiers (BUILD_ID, ABI names) are not reported.
    ("device-serial", re.compile(
        r"(?:(?i:\badb\b)[^\n]{0,40}?\b[A-Z0-9]{10,22}\b)"
        r"|(?:(?i:\bserial\b)[^\n]{0,20}?[:=]\s*[\"']?[A-Z0-9]{10,22}\b)")),
    # Android serials look like ZYPZ261A01M6870009D: uppercase alphanumeric,
    # mixed letters and digits, 12-24 chars. Kept case-sensitive so lowercase
    # hashes and identifiers are not reported.
    ("device-serial-token", re.compile(
        r"\b(?=[A-Z0-9]{12,24}\b)(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{12,24}\b")),
    # Personal home directories: username after the well-known prefix.
    ("windows-user-path", re.compile(r"\b[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9_.\-]+", re.I)),
    ("posix-user-path", re.compile(r"(?<![\w./\\])(?:/home/[A-Za-z0-9_.\-]+|/Users/[A-Za-z0-9_.\-]+)")),
]

# Known-benign matches: generic OS/toolchain locations that carry no identity.
ALLOW: list[re.Pattern[str]] = [
    re.compile(r"C:\\\\?Program Files\b", re.I),
    re.compile(r"C:\\\\?Windows\\\\?System32\b", re.I),
    re.compile(r"/home/runner\b"),
    re.compile(r"/Users/runner\b"),
    re.compile(r"/home/<"),
]


def blocked_reason(rel: str) -> str | None:
    """Reason to refuse this path outright, or None to let the content scan run."""
    name = rel.rsplit("/", 1)[-1]
    if name in BLOCKED_NAMES:
        return BLOCKED_NAMES[name]
    suffix = Path(name).suffix.lower()
    return BLOCKED_SUFFIXES.get(suffix)


def git_files(include_ignored: bool) -> list[str]:
    """Files a commit would contain, i.e. tracked plus untracked-but-not-ignored."""
    def ls(extra: list[str]) -> list[str]:
        try:
            out = subprocess.run(
                ["git", "-C", str(REPO), "ls-files", "-z", *extra],
                capture_output=True, check=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError):
            return []
        return [p.decode("utf-8", "replace") for p in out.split(b"\0") if p]

    files = ls(["--cached", "--others", "--exclude-standard"])
    if include_ignored:
        files += ls(["--others", "--ignored", "--exclude-standard"])
    return list(dict.fromkeys(files))


def walk_files() -> list[str]:
    found: list[str] = []
    for dirpath, dirnames, filenames in os.walk(REPO):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            full = Path(dirpath, name)
            found.append(full.relative_to(REPO).as_posix())
    return found


def is_text(path: Path) -> bool:
    if path.suffix.lower() not in TEXT_SUFFIXES:
        return False
    try:
        if path.stat().st_size > MAX_BYTES:
            return False
        with path.open("rb") as handle:
            return b"\0" not in handle.read(8000)
    except OSError:
        return False


def scan(verbose: bool, include_ignored: bool) -> int:
    files = git_files(include_ignored)
    if not files:
        files = walk_files()

    hits: list[tuple[str, str, int | None, str]] = []
    scanned = 0

    for rel in files:
        if rel in SKIP_PATHS:
            continue
        if SKIP_DIRS.intersection(rel.split("/")[:-1]):
            continue

        reason = blocked_reason(rel)
        if reason:
            hits.append(("blocked-file", rel, None, reason))
            continue

        path = REPO / rel
        if not path.is_file() or not is_text(path):
            continue
        scanned += 1
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for name, pattern in RULES:
                match = pattern.search(line)
                if not match:
                    continue
                if any(allowed.search(match.group(0)) for allowed in ALLOW):
                    continue
                hits.append((name, rel, lineno, line.strip()[:200]))

    for name, rel, lineno, line in hits:
        where = f"{rel}:{lineno}" if lineno else rel
        annotation = f"file={rel}" + (f",line={lineno}" if lineno else "")
        print(f"::error {annotation}::{name}: {line}")
        print(f"{name:18} {where}\n    {line}")

    if verbose:
        print(f"scanned {scanned} text files of {len(files)} entries")

    if hits:
        print(f"\n{len(hits)} potential secret(s) found. Fix before pushing.")
        return 1

    print(f"no secrets detected ({scanned} text files scanned)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan for secrets before publishing.")
    parser.add_argument("--all", action="store_true",
                        help="also scan gitignored files (local debugging only)")
    parser.add_argument("--verbose", action="store_true", help="print file counts")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if REPO == Path("/"):
        print("cannot determine repository root", file=sys.stderr)
        return 2

    return scan(verbose=args.verbose, include_ignored=args.all)


if __name__ == "__main__":
    sys.exit(main())
