# SPDX-License-Identifier: GPL-3.0-only
"""Minimizes Chrome's windows while ChromeDeck cannot be seen, and restores them.

usage: window-visibility.py hidden|visible

Uses libX11, which Chrome already needs, so no extra packages are required. Only the
windows minimized here are restored; their ids are kept in /tmp between the calls.
"""
import ctypes
import os
import sys

STATE_FILE = "/tmp/desktop-chrome-hidden-windows"

ul = ctypes.c_ulong
vp = ctypes.c_void_p
x = ctypes.CDLL("libX11.so.6")
x.XOpenDisplay.restype = vp
x.XOpenDisplay.argtypes = [ctypes.c_char_p]
x.XDefaultRootWindow.restype = ul
x.XDefaultRootWindow.argtypes = [vp]
x.XDefaultScreen.argtypes = [vp]
x.XInternAtom.restype = ul
x.XInternAtom.argtypes = [vp, ctypes.c_char_p, ctypes.c_int]
x.XGetWindowProperty.argtypes = [vp, ul, ul, ctypes.c_long, ctypes.c_long, ctypes.c_int, ul,
                                 ctypes.POINTER(ul), ctypes.POINTER(ctypes.c_int),
                                 ctypes.POINTER(ul), ctypes.POINTER(ul), ctypes.POINTER(vp)]
x.XIconifyWindow.argtypes = [vp, ul, ctypes.c_int]
x.XMapWindow.argtypes = [vp, ul]
x.XFree.argtypes = [vp]
x.XSync.argtypes = [vp, ctypes.c_int]
x.XCloseDisplay.argtypes = [vp]


def window_list(display, window, name):
    """Returns a 32-bit format window or atom list property; Xlib stores items as longs."""
    kind, fmt, count, after, data = ul(), ctypes.c_int(), ul(), ul(), vp()
    if x.XGetWindowProperty(display, window, x.XInternAtom(display, name, 0), 0, 1024, 0, 0,
                            ctypes.byref(kind), ctypes.byref(fmt), ctypes.byref(count),
                            ctypes.byref(after), ctypes.byref(data)) != 0 or not data:
        return []
    items = list(ctypes.cast(data, ctypes.POINTER(ul))[:count.value]) if fmt.value == 32 else []
    x.XFree(data)
    return items


def main():
    state = sys.argv[1]
    display = x.XOpenDisplay(None)
    if not display:
        sys.exit("Cannot open the X display")
    root = x.XDefaultRootWindow(display)
    # Bottom to top, so restoring keeps the window order.
    windows = window_list(display, root, b"_NET_CLIENT_LIST_STACKING")
    hidden_atom = x.XInternAtom(display, b"_NET_WM_STATE_HIDDEN", 0)
    if state == "hidden":
        minimized = [w for w in windows if hidden_atom not in window_list(display, w, b"_NET_WM_STATE")]
        for w in minimized:
            x.XIconifyWindow(display, w, x.XDefaultScreen(display))
        if minimized:
            with open(STATE_FILE, "w") as f:
                f.write(" ".join(map(str, minimized)))
    elif state == "visible":
        try:
            with open(STATE_FILE) as f:
                minimized = {int(w) for w in f.read().split()}
            os.remove(STATE_FILE)
        except FileNotFoundError:
            minimized = set()
        # Openbox restores and activates a minimized window when it is mapped again.
        for w in windows:
            if w in minimized:
                x.XMapWindow(display, w)
    x.XSync(display, 0)
    x.XCloseDisplay(display)


main()
