"""Saylo desktop app: a pywebview window (UI in ui/) on top of the dictation engine.
The page calls the Api methods below via window.pywebview.api; Python pushes live events
(recording / processing / result / error) to the page with saylo.on(event).
Build to an .exe with build.ps1."""
import array
import ctypes.wintypes
import json
import logging
import os
import queue
import sys
import threading
import time
from pathlib import Path

import httpx
import sounddevice as sd
import webview

import config
import pipeline
import store
import tray
from hotkey import HoldHotkey
from recorder import Recorder, is_bluetooth, list_input_devices, resolve_device

config.APP_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    filename=config.APP_DIR / "app.log", level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s", datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("app")

UI_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "ui"
# Bump when onboarding changes so existing users go through the new flow once.
ONBOARDING_VERSION = 2
HOTKEY_LABELS = {"ctrl": "Ctrl", "cmd": "Win", "alt": "Alt", "shift": "Shift"}


def save_groq_key(key: str) -> None:
    lines = config.USER_ENV.read_text().splitlines() if config.USER_ENV.exists() else []
    lines = [l for l in lines if not l.startswith("GROQ_API_KEY=")] + [f"GROQ_API_KEY={key}"]
    config.USER_ENV.write_text("\n".join(lines) + "\n")
    config.GROQ_API_KEY = key
    config.HAS_USER_KEY = True


class TrayPopup:
    """Saylo's own corner card (ui/popup.html) in place of a Windows toast. Public methods are called
    from the popup page; it closes itself after a few seconds."""
    WIDTH, HEIGHT, MARGIN = 380, 168, 16

    def __init__(self, on_open):
        self._on_open = on_open
        self._window = None

    def show(self):
        user32 = ctypes.windll.user32
        area = ctypes.wintypes.RECT()
        user32.SystemParametersInfoW(0x30, 0, ctypes.byref(area), 0)  # SPI_GETWORKAREA, physical px
        scale = user32.GetDpiForSystem() / 96  # pywebview takes logical px
        x = int(area.right / scale) - self.WIDTH - self.MARGIN
        y = int(area.bottom / scale) - self.HEIGHT - self.MARGIN
        in_use = user32.GetForegroundWindow()
        self._window = webview.create_window(
            "Saylo", str(UI_DIR / "popup.html"), js_api=self, width=self.WIDTH, height=self.HEIGHT,
            x=x, y=y, resizable=False, frameless=True, easy_drag=False, on_top=True, focus=False,
            background_color="#FFFFFF")
        # Keep it off the taskbar and Alt+Tab (tool window), and give it Windows 11 rounded corners.
        hwnd = self._window.native.Handle.ToInt32()
        style = user32.GetWindowLongW(hwnd, -20)  # GWL_EXSTYLE
        user32.SetWindowLongW(hwnd, -20, (style & ~0x40000) | 0x80)  # -WS_EX_APPWINDOW, +WS_EX_TOOLWINDOW
        user32.ShowWindow(hwnd, 0)  # re-show so the taskbar drops the button
        user32.ShowWindow(hwnd, 4)  # SW_SHOWNOACTIVATE
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 33, ctypes.byref(ctypes.c_int(2)), 4)  # round corners
        # pywebview activates every window it shows; hand focus back to the app the user is in.
        self._window.events.shown.wait(2)
        if in_use and user32.GetForegroundWindow() != in_use:
            user32.SetForegroundWindow(in_use)

    def hotkey(self) -> list:
        return [HOTKEY_LABELS.get(k, k.upper()) for k in config.HOTKEY.split("+")]

    def open_app(self):
        self.close()
        self._on_open()

    def close(self):
        if self._window:
            window, self._window = self._window, None
            window.destroy()


class Api:
    """Every public method is callable from the page. Private state uses _names so pywebview skips it."""

    def __init__(self):
        self._window = None
        self._recorder = Recorder()
        self._clips: queue.Queue = queue.Queue()
        self._dictation_on = store.settings().get("onboarded") == ONBOARDING_VERSION
        self._recording = False
        self._meter = None
        self._peak = 0
        self._hk = HoldHotkey(config.HOTKEY, self._hot_down, self._hot_up)
        self._hk.start()
        threading.Thread(target=self._worker, daemon=True).start()

    # ---------- events to the page ----------
    def _emit(self, kind: str, **data):
        if self._window:
            self._window.evaluate_js(f"window.saylo && saylo.on({json.dumps({'kind': kind, **data})})")

    # ---------- dictation ----------
    def _hot_down(self):
        if not self._dictation_on:
            return
        try:
            self._recorder.start(device=resolve_device(store.settings().get("mic")))
            self._recording = True
            self._emit("recording")
        except Exception as e:
            log.exception("mic start failed")
            self._emit("error", message=f"Microphone error: {e}")

    def _hot_up(self):
        if self._recording:
            self._recording = False
            wav, secs = self._recorder.stop()
            self._clips.put((wav, secs, time.perf_counter()))
            self._emit("processing")

    def _worker(self):
        while True:
            wav, secs, released = self._clips.get()
            try:
                r = pipeline.process(wav, secs, released, self._hk.any_held)
                log.info("clip=%.2fs asr=%.0fms clean=%.0fms paste=%.0fms total=%.0fms by=%s skipped=%s raw=%r text=%r",
                         r.seconds, r.asr_ms, r.clean_ms, r.paste_ms, r.total_ms, r.cleaned_by, r.skipped, r.raw, r.text)
                if r.skipped:
                    self._emit("skipped", reason=r.skipped)
                    continue
                entry = store.add_history(r.text, r.raw, r.seconds, r.total_ms)
                self._emit("result", entry=entry, asr_ms=round(r.asr_ms), clean_ms=round(r.clean_ms),
                           stats=store.stats(), raw_pasted=r.cleaned_by == "raw")
            except Exception as e:
                log.exception("dictation failed")
                msg = str(e)
                if isinstance(e, httpx.HTTPStatusError) and e.response.status_code == 429:
                    msg = "Rate limit reached. Wait a minute and try again."
                elif isinstance(e, httpx.HTTPError):
                    msg = "Couldn't reach the speech service. Check your internet connection."
                self._emit("error", message=msg)

    def dictation(self, on: bool):
        self._dictation_on = bool(on)

    # ---------- state ----------
    def state(self) -> dict:
        log.info("ui connected")
        s = store.settings()
        return {
            "onboarded": s.get("onboarded") == ONBOARDING_VERSION,
            "profile": store.profile(),
            "hotkey": [HOTKEY_LABELS.get(k, k.upper()) for k in config.HOTKEY.split("+")],
            "hotkey_ids": config.HOTKEY.split("+"),
            "has_user_key": config.HAS_USER_KEY,
            "has_default_key": bool(config.GROQ_API_KEY) and not config.HAS_USER_KEY,
            "stats": store.stats(),
            "log_path": str(config.APP_DIR / "app.log"),
        }

    def save_profile(self, patch: dict) -> dict:
        return store.update_profile(patch)

    def finish(self):
        store.update_settings(onboarded=ONBOARDING_VERSION)
        self._dictation_on = True

    def restart_setup(self):
        store.update_settings(onboarded=0)
        self._dictation_on = False

    def history(self, query: str = "") -> list:
        return store.history(query)

    def clear_history(self):
        store.clear_history()
        return store.stats()

    def stats(self) -> dict:
        return store.stats()

    def open_log(self):
        os.startfile(config.APP_DIR / "app.log")

    # ---------- API key ----------
    def check_key(self, key: str) -> dict:
        key = (key or "").strip()
        if not key:
            return {"ok": False, "error": "Paste your key first."}
        try:
            r = httpx.get("https://api.groq.com/openai/v1/models",
                          headers={"Authorization": f"Bearer {key}"}, timeout=15)
        except httpx.HTTPError as e:
            return {"ok": False, "error": f"Couldn't reach Groq: {e}"}
        if r.status_code != 200:
            return {"ok": False, "error": "That key was rejected. Check it and try again."
                    if r.status_code in (401, 403) else f"Groq returned {r.status_code}."}
        save_groq_key(key)
        return {"ok": True}

    # ---------- microphone ----------
    def mics(self, rescan: bool = False) -> dict:
        if rescan and (self._recording or self._meter):
            rescan = False  # PortAudio can't rescan while a stream is open
        devices = [{"name": n, "label": label, "bluetooth": is_bluetooth(n)}
                   for n, label in list_input_devices(rescan=rescan)]
        saved = store.settings().get("mic")
        return {"devices": devices, "selected": saved if any(d["name"] == saved for d in devices) else None}

    def set_mic(self, name):
        store.update_settings(mic=name or None)
        log.info("mic set to %r", name)
        if self._meter:
            self.meter_start()

    def mic_permission(self) -> str:
        try:
            s = sd.RawInputStream(device=resolve_device(store.settings().get("mic")),
                                  samplerate=config.SAMPLE_RATE, channels=1, dtype="int16")
            s.close()
            return "allowed"
        except Exception as e:
            log.info("mic permission check failed: %s", e)
            return "blocked"

    def open_mic_settings(self):
        os.startfile("ms-settings:privacy-microphone")

    def meter_start(self) -> dict:
        self.meter_stop()
        self._peak = 0

        def cb(data, frames, t, status):
            samples = array.array("h", bytes(data))
            self._peak = max((abs(x) for x in samples), default=0)

        try:
            self._meter = sd.RawInputStream(device=resolve_device(store.settings().get("mic")),
                                            samplerate=config.SAMPLE_RATE, channels=1, dtype="int16", callback=cb)
            self._meter.start()
            return {"ok": True}
        except Exception as e:
            log.exception("meter failed")
            self._meter = None
            return {"ok": False, "error": str(e)}

    def meter_level(self) -> int:
        return min(100, round(self._peak / 120))

    def meter_stop(self):
        if self._meter:
            self._meter.stop()
            self._meter.close()
            self._meter = None

    # ---------- shortcut ----------
    def keys(self) -> dict:
        return {"pressed": sorted(self._hk.pressed & self._hk.combo), "active": self._hk.active}

    def _shutdown(self):
        self.meter_stop()
        self._hk.stop()

    # ---------- window / tray ----------
    def _on_closing(self):
        """Closing the window hides it to the tray; only Quit (tray menu) really exits."""
        if self._quitting:
            return True
        self.meter_stop()
        self._window.hide()
        log.info("window hidden to tray")
        if not store.settings().get("tray_tip_shown"):
            store.update_settings(tray_tip_shown=True)
            # Off the GUI thread: pywebview builds a second window synchronously on it.
            threading.Thread(target=self._popup.show, daemon=True).start()
        return False

    def _show_window(self):
        self._window.show()
        self._window.restore()

    def _quit(self):
        log.info("quit from tray")
        self._quitting = True
        self._popup.close()
        self._tray.stop()
        self._window.destroy()


def main():
    # --quit: stop a running Saylo (used by the installer before updating/uninstalling).
    # --tray: start hidden in the tray (used by the Windows startup entry).
    if "--quit" in sys.argv:
        tray.signal_running("quit")
        return
    to_tray = "--tray" in sys.argv
    if tray.signal_running("ping" if to_tray else "show"):
        return  # Saylo is already running; a normal launch brings its window up instead
    api = Api()
    hidden = to_tray and api._dictation_on  # first run still needs the setup window
    window = webview.create_window("Saylo", str(UI_DIR / "index.html"), js_api=api, hidden=hidden,
                                   width=1120, height=740, min_size=(940, 640), background_color="#FBF9F6")
    api._window = window
    api._quitting = False
    api._popup = TrayPopup(on_open=api._show_window)
    api._tray = tray.start_tray(on_open=api._show_window, on_quit=api._quit)
    tray.serve_commands({"show": api._show_window, "quit": api._quit})
    window.events.closing += api._on_closing
    window.events.closed += api._shutdown
    log.info("app started%s", " in tray" if hidden else "")
    webview.start(http_server=True, private_mode=False, storage_path=str(config.APP_DIR / "webview"))


if __name__ == "__main__":
    main()
