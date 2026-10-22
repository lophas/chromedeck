# SPDX-License-Identifier: GPL-3.0-only
"""ChromeDeck's helper on the X display: Chrome's display scale and its visibility.

usage: session-x11.py SCALE SCALE_PIPE VISIBILITY_PIPE

Display scale: Chrome on Linux takes its scale from the desktop's Xft/DPI setting and
follows changes of it, so moving between the phone and a monitor needs no restart. This
process owns the XSETTINGS selection, as a desktop's settings daemon would, and sets
Xft/DPI to SCALE × 96; each line written to SCALE_PIPE, such as "1.25", sets a new scale.

Chrome also sets a minimum window size, from its toolbar, in its own units; at a large
scale on the narrow phone screen that is wider than the screen, and the window manager
would then keep a maximized window wider than the screen. Chrome fits a narrower window
well, so a minimum larger than the screen is removed whenever Chrome sets it.

Visibility: ChromeDeck writes "hidden" or "visible" to VISIBILITY_PIPE when it leaves or
returns to the screen. Minimized Chrome stops drawing pages and slows down timers, as
Chrome on a phone does in the background; audio keeps playing. "visible" restores every
minimized window, so a restore never depends on an earlier "hidden".

Both pipes stay open in this one long-running process, so no message is lost between
writers. Uses libX11, which Chrome already needs.
"""
import ctypes
import os
import select
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
x.XGetWindowProperty.argtypes = [vp, ul, ul, ctypes.c_long, ctypes.c_long, ctypes.c_int, ul,
                                 ctypes.POINTER(ul), ctypes.POINTER(ctypes.c_int),
                                 ctypes.POINTER(ul), ctypes.POINTER(ul), ctypes.POINTER(vp)]
x.XFree.argtypes = [vp]
x.XGetGeometry.argtypes = [vp, ul, ctypes.POINTER(ul), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                           ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
                           ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
x.XSelectInput.argtypes = [vp, ul, ctypes.c_long]
x.XConnectionNumber.argtypes = [vp]
x.XPending.argtypes = [vp]
x.XNextEvent.argtypes = [vp, vp]
x.XIconifyWindow.argtypes = [vp, ul, ctypes.c_int]
x.XDefaultScreen.argtypes = [vp]
x.XMapWindow.argtypes = [vp, ul]
PROPERTY_CHANGE_MASK = 1 << 22
PROPERTY_NOTIFY = 28


class SizeHints(ctypes.Structure):
    _fields_ = [("flags", ctypes.c_long)] + [(name, ctypes.c_int) for name in (
        "x", "y", "width", "height", "min_width", "min_height", "max_width", "max_height",
        "width_inc", "height_inc", "min_aspect_x", "min_aspect_y", "max_aspect_x", "max_aspect_y",
        "base_width", "base_height", "win_gravity")]


x.XGetWMNormalHints.argtypes = [vp, ul, ctypes.POINTER(SizeHints), ctypes.POINTER(ctypes.c_long)]
x.XSetWMNormalHints.argtypes = [vp, ul, ctypes.POINTER(SizeHints)]
P_MIN_SIZE = 1 << 4


class ClientMessage(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ul), ("send_event", ctypes.c_int),
                ("display", vp), ("window", ul), ("message_type", ul), ("format", ctypes.c_int),
                ("data", ctypes.c_long * 5)]


class PropertyEvent(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ul), ("send_event", ctypes.c_int),
                ("display", vp), ("window", ul), ("atom", ul), ("time", ul), ("state", ctypes.c_int)]


class Event(ctypes.Union):
    _fields_ = [("client", ClientMessage), ("property", PropertyEvent), ("pad", ctypes.c_long * 24)]


def atom_list(display, window, name):
    """A 32-bit format window or atom list property; Xlib stores items as longs."""
    kind, fmt, count, after, data = ul(), ctypes.c_int(), ul(), ul(), vp()
    if x.XGetWindowProperty(display, window, x.XInternAtom(display, name, 0), 0, 1024, 0, 0,
                            ctypes.byref(kind), ctypes.byref(fmt), ctypes.byref(count),
                            ctypes.byref(after), ctypes.byref(data)) != 0 or not data:
        return []
    items = list(ctypes.cast(data, ctypes.POINTER(ul))[:count.value]) if fmt.value == 32 else []
    x.XFree(data)
    return items


def screen_size(display, root):
    width, height, ignored = ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint()
    nothing, at = ul(), ctypes.c_int()
    x.XGetGeometry(display, root, ctypes.byref(nothing), ctypes.byref(at), ctypes.byref(at),
                   ctypes.byref(width), ctypes.byref(height), ctypes.byref(ignored), ctypes.byref(ignored))
    return width.value, height.value


def fit_minimum(display, root, window):
    """Removes a minimum window size that is larger than the screen."""
    hints, supplied = SizeHints(), ctypes.c_long()
    if not x.XGetWMNormalHints(display, window, ctypes.byref(hints), ctypes.byref(supplied)) \
            or not hints.flags & P_MIN_SIZE:
        return
    width, height = screen_size(display, root)
    if hints.min_width > width or hints.min_height > height:
        hints.flags &= ~P_MIN_SIZE
        hints.min_width = hints.min_height = 0
        x.XSetWMNormalHints(display, window, ctypes.byref(hints))


def watch_windows(display, root, watched):
    """Follows size hint changes of new top-level windows, and fits them at once."""
    for window in atom_list(display, root, b"_NET_CLIENT_LIST"):
        if window not in watched:
            watched.add(window)
            x.XSelectInput(display, window, PROPERTY_CHANGE_MASK)
        fit_minimum(display, root, window)


def set_visible(display, root, visible):
    """Minimizes Chrome's windows, or restores every minimized one."""
    hidden = x.XInternAtom(display, b"_NET_WM_STATE_HIDDEN", 0)
    # Bottom to top, so restoring keeps the window order.
    for window in atom_list(display, root, b"_NET_CLIENT_LIST_STACKING"):
        minimized = hidden in atom_list(display, window, b"_NET_WM_STATE")
        if visible and minimized:
            # The window manager restores and activates a minimized window that is mapped.
            x.XMapWindow(display, window)
        elif not visible and not minimized:
            x.XIconifyWindow(display, window, x.XDefaultScreen(display))
    x.XFlush(display)


def lines(fd, pending):
    """Complete lines read from a pipe, and the incomplete rest."""
    try:
        pending += os.read(fd, 4096)
    except BlockingIOError:
        pass
    *complete, rest = pending.split(b"\n")
    return [line.strip() for line in complete], rest


def settings(serial, dpi):
    """The _XSETTINGS_SETTINGS property: one integer setting, Xft/DPI in 1/1024 dpi."""
    name = b"Xft/DPI"
    entry = struct.pack("<BBH", 0, 0, len(name)) + name + b"\0" * (-len(name) % 4)
    entry += struct.pack("<Ii", serial, int(dpi * 1024))
    return struct.pack("<B3xII", 0, serial, 1) + entry


def main():
    scale, scale_pipe, visibility_pipe = float(sys.argv[1]), sys.argv[2], sys.argv[3]
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

    client_list = x.XInternAtom(display, b"_NET_CLIENT_LIST", 0)
    normal_hints = x.XInternAtom(display, b"WM_NORMAL_HINTS", 0)
    x.XSelectInput(display, root, PROPERTY_CHANGE_MASK)
    watched = set()
    watch_windows(display, root, watched)
    x.XFlush(display)

    # Opened for reading and writing, so that the pipes stay open between writers.
    scales = os.open(scale_pipe, os.O_RDWR | os.O_NONBLOCK)
    visibility = os.open(visibility_pipe, os.O_RDWR | os.O_NONBLOCK)
    pending_scale = pending_visibility = b""
    connection = x.XConnectionNumber(display)
    event = Event()
    while True:
        while x.XPending(display):
            x.XNextEvent(display, ctypes.byref(event))
            if event.property.type != PROPERTY_NOTIFY:
                continue
            if event.property.window == root and event.property.atom == client_list:
                watch_windows(display, root, watched)
            elif event.property.atom == normal_hints:
                fit_minimum(display, root, event.property.window)
        x.XFlush(display)
        ready, _, _ = select.select([connection, scales, visibility], [], [])
        # A bad message or a vanished window must not end the helper.
        try:
            if visibility in ready:
                received, pending_visibility = lines(visibility, pending_visibility)
                for line in received:
                    if line in (b"hidden", b"visible"):
                        set_visible(display, root, line == b"visible")
            if scales in ready:
                received, pending_scale = lines(scales, pending_scale)
                for line in received:
                    value = float(line)
                    if 0.5 <= value <= 8:
                        serial += 1
                        publish(value)
        except Exception as error:
            print("ChromeDeck X11 helper:", error, file=sys.stderr)


main()
