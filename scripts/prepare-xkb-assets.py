#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Copy build-host xkb-data into generated Android assets."""
import pathlib
import shutil
import sys

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: prepare-xkb-assets.py SOURCE DESTINATION")
    source, destination = map(pathlib.Path, sys.argv[1:])
    if not (source / "rules" / "evdev").is_file():
        raise SystemExit("Missing xkb-data. Install it with: apt-get install xkb-data")
    destination.mkdir(parents=True, exist_ok=True)
    # Dereference the package's aliases: Android assets do not retain Unix symlinks.
    shutil.copytree(source, destination / "xkb", dirs_exist_ok=True, symlinks=False)
    docs = pathlib.Path("/usr/share/doc/xkb-data")
    for name in ("copyright",):
        if (docs / name).is_file():
            shutil.copyfile(docs / name, destination / ("xkb-data-" + name))
    print("Prepared embedded XKB assets")

if __name__ == "__main__":
    main()
