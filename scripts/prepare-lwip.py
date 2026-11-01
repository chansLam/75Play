#!/usr/bin/env python3
"""Fetch the exact lwIP source used by the userspace NCM relay.

Requires git and network access for the first build. Subsequent builds reuse the
verified checkout; never download from unpinned HEAD.
"""
from pathlib import Path
import subprocess
import tempfile
import shutil

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "shared/src/main/jni/lwip"
COMMIT = "6ca936f6b588cee702c638eee75c2436e6cf75de"  # STABLE-2_1_3_RELEASE
REPO = "https://github.com/lwip-tcpip/lwip.git"

def run(*args):
    subprocess.run(args, check=True)

def main():
    marker = DEST / ".diplay-lwip-commit"
    if marker.is_file() and marker.read_text().strip() == COMMIT and (DEST / "src/include/lwip/opt.h").is_file():
        return
    with tempfile.TemporaryDirectory(prefix="diplay-lwip-") as td:
        checkout = Path(td) / "lwip"
        run("git", "clone", "--no-checkout", REPO, str(checkout))
        run("git", "-C", str(checkout), "checkout", "--detach", COMMIT)
        actual = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if actual != COMMIT:
            raise RuntimeError("Unexpected lwIP revision")
        if DEST.exists():
            shutil.rmtree(DEST)
        shutil.copytree(checkout, DEST, ignore=shutil.ignore_patterns(".git"))
        marker.write_text(COMMIT + "\n")
    for f in ("src/include/lwip/opt.h", "src/include/lwip/tcp.h", "src/core/init.c"):
        if not (DEST / f).is_file():
            raise RuntimeError(f"Missing lwIP source: {f}")

if __name__ == "__main__":
    main()
