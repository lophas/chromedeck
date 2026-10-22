#!/usr/bin/python3
# SPDX-License-Identifier: GPL-3.0-only
"""ChromeDeck keyboard bridge: Chrome's native messaging host for the ChromeDeck keyboard
extension. Each message, {"keyboard": "show"} or {"keyboard": "hide"}, is written to
/tmp/desktop-chrome-keyboard with a counter, which the Android app watches."""
import json
import os
import struct
import sys

PATH = "/tmp/desktop-chrome-keyboard"
count = 0
while True:
    header = sys.stdin.buffer.read(4)
    if len(header) < 4:
        break
    message = json.loads(sys.stdin.buffer.read(struct.unpack("=I", header)[0]))
    state = message.get("keyboard")
    if state in ("show", "hide"):
        count += 1
        with open(PATH + ".tmp", "w") as f:
            f.write("%d %s\n" % (count, state))
        os.replace(PATH + ".tmp", PATH)
