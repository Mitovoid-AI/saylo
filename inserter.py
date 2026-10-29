"""Pastes text into the focused app: save clipboard -> paste with Ctrl+V -> restore clipboard."""
import time

import pyperclip
from pynput import keyboard

_kb = keyboard.Controller()

# Give the target app time to read the clipboard before restoring it.
PASTE_SETTLE_SECONDS = 0.15


def insert(text: str) -> None:
    try:
        previous = pyperclip.paste()
    except pyperclip.PyperclipException:
        previous = None
    pyperclip.copy(text)
    with _kb.pressed(keyboard.Key.ctrl):
        _kb.press("v")
        _kb.release("v")
    time.sleep(PASTE_SETTLE_SECONDS)
    if previous is not None:
        pyperclip.copy(previous)
