# SPDX-License-Identifier: GPL-3.0-only
"""Gives Chrome its display scale through XSETTINGS, and changes it while Chrome runs.

usage: display-scale.py PIPE SCALE

Chrome on Linux takes its scale from the desktop's Xft/DPI setting and follows changes
of it, so moving between the phone and a monitor needs no restart. This process owns
the XSETTINGS selection, as a desktop's settings daemon would, and sets Xft/DPI to
SCALE × 96; each line written to PIPE, such as "1.25", sets a new scale.
Uses libX11, which Chrome already needs.
"""
import ctypes
import os
import struct
import sys

ul = ctypes.c_ulong
vp = ctypes.c_void_p
x = ctypes.CDLL("libX11.so.6")
x.XOpenDisplay.restype = vp
x.XOpenDisplay.argtypes = [ctypes.c_char_p]
x.XDefaultRootWindow.restype = ul
x.XDefaultRootWindow.argtypes = [vp]
x.XInternAtom.restype = ul
x.XInternAtom.argtypes = [vp, ctypes.c_char_p, ctypes.c_int]
x.XCreateSimpleWindow.restype = ul
x.XCreateSimpleWindow.argtypes = [vp, ul, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint,
                                  ctypes.c_uint, ul, ul]
x.XSetSelectionOwner.argtypes = [vp, ul, ul, ul]
x.XGetSelectionOwner.restype = ul
x.XGetSelectionOwner.argtypes = [vp, ul]
x.XChangeProperty.argtypes = [vp, ul, ul, ul, ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
x.XSendEvent.argtypes = [vp, ul, ctypes.c_int, ctypes.c_long, vp]
x.XFlush.argtypes = [vp]


class ClientMessage(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ul), ("send_event", ctypes.c_int),
                ("display", vp), ("window", ul), ("message_type", ul), ("format", ctypes.c_int),
                ("data", ctypes.c_long * 5)]


class Event(ctypes.Union):
    _fields_ = [("client", ClientMessage), ("pad", ctypes.c_long * 24)]


def settings(serial, dpi):
    """The _XSETTINGS_SETTINGS property: one integer setting, Xft/DPI in 1/1024 dpi."""
    name = b"Xft/DPI"
    entry = struct.pack("<BBH", 0, 0, len(name)) + name + b"\0" * (-len(name) % 4)
    entry += struct.pack("<Ii", serial, int(dpi * 1024))
    return struct.pack("<B3xII", 0, serial, 1) + entry


def main():
    pipe, scale = sys.argv[1], float(sys.argv[2])
    display = x.XOpenDisplay(None)
    if not display:
        sys.exit("Cannot open the X display")
    root = x.XDefaultRootWindow(display)
    window = x.XCreateSimpleWindow(display, root, 0, 0, 1, 1, 0, 0, 0)
    selection = x.XInternAtom(display, b"_XSETTINGS_S0", 0)
    prop = x.XInternAtom(display, b"_XSETTINGS_SETTINGS", 0)
    serial = 0

    def publish(value):
        data = settings(serial, value * 96)
        x.XChangeProperty(display, window, prop, prop, 8, 0, data, len(data))
        x.XFlush(display)

    publish(scale)
    x.XSetSelectionOwner(display, selection, window, 0)
    if x.XGetSelectionOwner(display, selection) != window:
        sys.exit("Another program owns the XSETTINGS selection")
    # Tell clients that a settings manager is now running.
    event = Event()
    event.client.type = 33  # ClientMessage
    event.client.window = root
    event.client.message_type = x.XInternAtom(display, b"MANAGER", 0)
    event.client.format = 32
    event.client.data[:] = [0, selection, window, 0, 0]
    x.XSendEvent(display, root, 0, 1 << 17, ctypes.byref(event))  # StructureNotifyMask
    x.XFlush(display)

    # Opened for reading and writing, so that the pipe stays open between writers.
    with open(os.open(pipe, os.O_RDWR), "r") as commands:
        for line in commands:
            try:
                value = float(line)
            except ValueError:
                continue
            if 0.5 <= value <= 8:
                serial += 1
                publish(value)


main()
