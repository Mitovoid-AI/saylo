"""Global hold-to-talk listener: fires on_down when all hotkey keys are held, on_up when one is released."""
import logging

from pynput import keyboard

log = logging.getLogger(__name__)

# Fold left/right variants into one name so "ctrl" matches either Ctrl key.
_ALIASES = {
    keyboard.Key.ctrl_l: "ctrl", keyboard.Key.ctrl_r: "ctrl", keyboard.Key.ctrl: "ctrl",
    keyboard.Key.cmd_l: "cmd", keyboard.Key.cmd_r: "cmd", keyboard.Key.cmd: "cmd",
    keyboard.Key.alt_l: "alt", keyboard.Key.alt_r: "alt", keyboard.Key.alt_gr: "alt", keyboard.Key.alt: "alt",
    keyboard.Key.shift_l: "shift", keyboard.Key.shift_r: "shift", keyboard.Key.shift: "shift",
}

# An unassigned virtual-key code. Tapping it while Win is held stops Windows
# from opening the Start menu when Win is released.
_START_MENU_SUPPRESS_VK = 0xE8


def _name(key) -> str | None:
    if key in _ALIASES:
        return _ALIASES[key]
    if isinstance(key, keyboard.Key):
        return key.name
    if isinstance(key, keyboard.KeyCode) and key.char:
        return key.char.lower()
    return None


class HoldHotkey:
    def __init__(self, combo: str, on_down, on_up):
        self.combo = {k.strip().lower() for k in combo.split("+") if k.strip()}
        self.on_down, self.on_up = on_down, on_up
        self.pressed: set[str] = set()
        self.active = False
        self._controller = keyboard.Controller()
        self._listener = keyboard.Listener(on_press=self._press, on_release=self._release)

    def _press(self, key):
        name = _name(key)
        if name is None:
            return
        self.pressed.add(name)
        if not self.active and self.combo <= self.pressed:
            self.active = True
            if "cmd" in self.combo:
                vk = keyboard.KeyCode.from_vk(_START_MENU_SUPPRESS_VK)
                self._controller.press(vk)
                self._controller.release(vk)
            self.on_down()

    def _release(self, key):
        name = _name(key)
        self.pressed.discard(name)
        if self.active and name in self.combo:
            self.active = False
            self.on_up()

    def any_held(self) -> bool:
        return bool(self.combo & self.pressed)

    def start(self):
        self._listener.start()
        log.info("Listening for hotkey %s", "+".join(sorted(self.combo)))

    def join(self):
        self._listener.join()

    def stop(self):
        self._listener.stop()
