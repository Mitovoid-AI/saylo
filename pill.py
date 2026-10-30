"""The pill above the taskbar while you dictate: wave bars that follow the mic while the shortcut is held,
then "Cleaning up", or a short message. It is a plain Win32 layered window drawn with Pillow rather than a
webview: it needs real transparency (WebView2 can't do layered windows), must never take focus (the paste
has to land in the app in use) and lets clicks pass through. One thread owns the window and draws it."""
import ctypes
import ctypes.wintypes as w
import logging
import math
import queue
import threading
import time

from PIL import Image, ImageDraw, ImageFilter, ImageFont

log = logging.getLogger(__name__)

# Private DLL handles, so these argtypes don't change ctypes.windll for the rest of the app.
user32 = ctypes.WinDLL("user32")
gdi32 = ctypes.WinDLL("gdi32")
kernel32 = ctypes.WinDLL("kernel32")

WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, ctypes.c_uint, w.WPARAM, w.LPARAM)
user32.DefWindowProcW.argtypes = [w.HWND, ctypes.c_uint, w.WPARAM, w.LPARAM]
user32.DefWindowProcW.restype = ctypes.c_ssize_t
user32.CreateWindowExW.argtypes = [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int,
                                   ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, w.LPVOID]
user32.CreateWindowExW.restype = w.HWND
user32.GetDC.argtypes = [w.HWND]
user32.GetDC.restype = w.HDC
user32.ShowWindow.argtypes = [w.HWND, ctypes.c_int]
user32.SetWindowPos.argtypes = [w.HWND, w.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
user32.DestroyWindow.argtypes = [w.HWND]
user32.PeekMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, ctypes.c_uint, ctypes.c_uint, ctypes.c_uint]
user32.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
gdi32.CreateCompatibleDC.argtypes = [w.HDC]
gdi32.CreateCompatibleDC.restype = w.HDC
gdi32.SelectObject.argtypes = [w.HDC, w.HGDIOBJ]
gdi32.SelectObject.restype = w.HGDIOBJ
kernel32.GetModuleHandleW.restype = w.HMODULE


class WNDCLASSEXW(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.c_uint), ("style", ctypes.c_uint), ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int), ("hInstance", w.HINSTANCE),
                ("hIcon", w.HICON), ("hCursor", w.HANDLE), ("hbrBackground", w.HBRUSH),
                ("lpszMenuName", w.LPCWSTR), ("lpszClassName", w.LPCWSTR), ("hIconSm", w.HICON)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", w.DWORD), ("biWidth", w.LONG), ("biHeight", w.LONG), ("biPlanes", w.WORD),
                ("biBitCount", w.WORD), ("biCompression", w.DWORD), ("biSizeImage", w.DWORD),
                ("biXPelsPerMeter", w.LONG), ("biYPelsPerMeter", w.LONG), ("biClrUsed", w.DWORD),
                ("biClrImportant", w.DWORD)]


class BLENDFUNCTION(ctypes.Structure):
    _fields_ = [("BlendOp", ctypes.c_ubyte), ("BlendFlags", ctypes.c_ubyte),
                ("SourceConstantAlpha", ctypes.c_ubyte), ("AlphaFormat", ctypes.c_ubyte)]


user32.UpdateLayeredWindow.argtypes = [w.HWND, w.HDC, ctypes.POINTER(w.POINT), ctypes.POINTER(w.SIZE), w.HDC,
                                       ctypes.POINTER(w.POINT), w.COLORREF, ctypes.POINTER(BLENDFUNCTION), w.DWORD]
gdi32.CreateDIBSection.argtypes = [w.HDC, ctypes.POINTER(BITMAPINFOHEADER), ctypes.c_uint,
                                   ctypes.POINTER(ctypes.c_void_p), w.HANDLE, w.DWORD]
gdi32.CreateDIBSection.restype = w.HBITMAP


# Click-through, see-through, no taskbar button, always on top, never activated.
EX_STYLE = 0x80000 | 0x20 | 0x80 | 0x8 | 0x8000000  # LAYERED | TRANSPARENT | TOOLWINDOW | TOPMOST | NOACTIVATE
INK, CREAM, MINT, AMBER = (22, 20, 27), (251, 249, 246), (127, 224, 194), (244, 162, 89)
SHAPE = [.35, .5, .7, .85, .95, 1, .95, .85, .7, .5, .35]  # bar heights, tallest in the middle
SS = 2  # draw at 2x and scale down, for smooth edges


class Pill:
    WIDTH, HEIGHT, MARGIN = 300, 72, 6  # logical px; the pill is centred in it, 10 px above the bottom
    MESSAGE_SECONDS = 2
    FPS = 30

    def __init__(self, level):
        """level() returns the mic loudness, 0-100, for the bars."""
        self._level = level
        self._states: queue.Queue = queue.Queue()
        self._shadows: dict[int, Image.Image] = {}
        threading.Thread(target=self._run, daemon=True, name="pill").start()

    def show(self, state: str, text: str = "", seconds: float = MESSAGE_SECONDS):
        """state is listening, working, retrying (text, stays up), message (text, hides after seconds) or
        hidden. Never blocks the caller."""
        self._states.put((state, text, seconds))

    def close(self):
        self._states.put(("quit", "", 0))

    # ---------- window ----------
    def _create(self):
        # Per-monitor DPI aware for this thread, whatever the process is at the time (pywebview sets the
        # process later): the pill is then measured and drawn in real pixels, sharp at 125% or 150%.
        user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))  # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
        self._proc = WNDPROC(lambda h, m, wp, lp: user32.DefWindowProcW(h, m, wp, lp))  # kept: ctypes needs it alive
        hinst = kernel32.GetModuleHandleW(None)
        wc = WNDCLASSEXW(cbSize=ctypes.sizeof(WNDCLASSEXW), lpfnWndProc=self._proc, hInstance=hinst,
                         lpszClassName="SayloPill")
        user32.RegisterClassExW(ctypes.byref(wc))
        self._scale = user32.GetDpiForSystem() / 96
        area = w.RECT()
        user32.SystemParametersInfoW(0x30, 0, ctypes.byref(area), 0)  # SPI_GETWORKAREA
        self._w, self._h = round(self.WIDTH * self._scale), round(self.HEIGHT * self._scale)
        self._pos = w.POINT((area.left + area.right - self._w) // 2, area.bottom - self._h - round(self.MARGIN * self._scale))
        self._hwnd = user32.CreateWindowExW(EX_STYLE, "SayloPill", "Saylo", 0x80000000,  # WS_POPUP
                                            self._pos.x, self._pos.y, self._w, self._h, None, None, hinst, None)
        # A 32-bit top-down bitmap the frames are copied into, then handed to UpdateLayeredWindow.
        bmi = BITMAPINFOHEADER(biSize=ctypes.sizeof(BITMAPINFOHEADER), biWidth=self._w, biHeight=-self._h,
                               biPlanes=1, biBitCount=32)
        self._screen = user32.GetDC(None)
        self._dc = gdi32.CreateCompatibleDC(self._screen)
        self._bits = ctypes.c_void_p()
        gdi32.SelectObject(self._dc, gdi32.CreateDIBSection(self._screen, ctypes.byref(bmi), 0, ctypes.byref(self._bits), None, 0))
        self._font = self._load_font(13 * self._scale * SS)

    @staticmethod
    def _load_font(size):
        for name in ("seguisb.ttf", "segoeui.ttf"):
            try:
                return ImageFont.truetype(name, round(size))
            except OSError:
                pass
        return ImageFont.load_default(round(size))

    def _pump(self):
        msg = w.MSG()
        while user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 1):  # PM_REMOVE
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

    def _run(self):
        try:
            self._create()
        except Exception:
            log.exception("couldn't create the listening pill")
            return
        state, content, shown, appear, smooth = "hidden", ("listening", ""), False, 0.0, 0.0
        hide_at, last = None, time.perf_counter()
        while True:
            self._pump()  # a hidden window must still answer system broadcasts
            try:
                state, text, seconds = self._states.get(timeout=1 / self.FPS if shown else 0.1)
                if state == "quit":
                    user32.DestroyWindow(self._hwnd)
                    return
                if state != "hidden":
                    content = (state, text)
                hide_at = time.perf_counter() + seconds if state == "message" else None
            except queue.Empty:
                pass
            now = time.perf_counter()
            if hide_at and now > hide_at:
                state, hide_at = "hidden", None
            dt, last = now - last, now
            target = 0.0 if state == "hidden" else 1.0
            if target and not shown:
                user32.ShowWindow(self._hwnd, 4)  # SW_SHOWNOACTIVATE
                user32.SetWindowPos(self._hwnd, w.HWND(-1), 0, 0, 0, 0, 0x1 | 0x2 | 0x10)  # topmost, no move/size/activate
                shown = True
            if not shown:
                continue
            appear = min(1.0, appear + dt / 0.15) if target else max(0.0, appear - dt / 0.15)
            level = self._level() if content[0] == "listening" and state != "hidden" else 0
            smooth += (min(1.0, math.sqrt(level / 100) * 1.25) - smooth) * 0.35
            try:
                self._paint(*content, appear, smooth, now)
            except Exception:
                log.exception("pill paint failed")
            if not target and appear == 0:
                user32.ShowWindow(self._hwnd, 0)  # SW_HIDE
                shown = False

    # ---------- drawing ----------
    def _shadow(self, pill_w, pill_h, k):
        key = round(pill_w)
        if key not in self._shadows:
            pad = round(16 * k)
            img = Image.new("L", (round(pill_w) + 2 * pad, round(pill_h) + 2 * pad), 0)
            ImageDraw.Draw(img).rounded_rectangle([pad, pad, pad + pill_w, pad + pill_h], radius=pill_h / 2, fill=90)
            self._shadows[key] = (img.filter(ImageFilter.GaussianBlur(9 * k)), pad)
        return self._shadows[key]

    def _paint(self, state, text, appear, smooth, now):
        k = self._scale * SS  # logical px -> canvas px
        label = {"listening": "Listening", "working": "Cleaning up"}.get(state, text)
        dot = state in ("message", "retrying")
        lead = 8 * k if dot else (len(SHAPE) * 3 + (len(SHAPE) - 1) * 3) * k  # dot or bars
        pill_w = 16 * k + lead + 10 * k + self._font.getlength(label) + 18 * k
        pill_h = 40 * k
        cw, ch = self._w * SS, self._h * SS
        left = (cw - pill_w) / 2
        top = ch - 10 * k - pill_h + (1 - appear) * 14 * k  # slides up as it appears
        canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        shadow, pad = self._shadow(pill_w, pill_h, k)
        canvas.paste((*INK, 255), (round(left) - pad, round(top + 6 * k) - pad), shadow)
        d = ImageDraw.Draw(canvas)
        d.rounded_rectangle([left, top, left + pill_w, top + pill_h], radius=pill_h / 2, fill=(*INK, 255),
                            outline=(255, 255, 255, 26), width=max(1, round(k)))
        x, cy = left + 16 * k, top + pill_h / 2
        if dot:  # retrying: the dot pulses (fades towards the pill colour) while it keeps trying
            f = 1 if state == "message" else 0.6 + 0.4 * math.sin(now * 5)
            d.ellipse([x, cy - 4 * k, x + 8 * k, cy + 4 * k],
                      fill=(*(round(i + (a - i) * f) for i, a in zip(INK, AMBER)), 255))
        else:
            for i, s in enumerate(SHAPE):
                if state == "listening":  # a slow idle ripple, rising with the voice
                    idle = 2 * (1 + math.sin(now * 3 + i * 0.7))
                    h = 4 + max(idle, 20 * smooth * s * (0.65 + 0.35 * math.sin(now * 9 + i * 1.3)))
                else:  # working: a wave running through the bars
                    h = 4 + 12 * (0.5 - 0.5 * math.cos(2 * math.pi * ((now - i * 0.08) % 1.0)))
                bx = x + i * 6 * k
                d.rounded_rectangle([bx, cy - h * k / 2, bx + 3 * k, cy + h * k / 2], radius=1.5 * k,
                                    fill=(*(CREAM if state == "listening" else MINT), 255))
        d.text((x + lead + 10 * k, cy), label, font=self._font, fill=(*CREAM, 255), anchor="lm")
        # Premultiplied BGRA is what UpdateLayeredWindow wants.
        r, g, b, a = canvas.convert("RGBa").resize((self._w, self._h), Image.LANCZOS).split()
        data = Image.merge("RGBa", (b, g, r, a)).tobytes()
        ctypes.memmove(self._bits, data, len(data))
        blend = BLENDFUNCTION(0, 0, round(255 * appear), 1)  # AC_SRC_OVER, fade, AC_SRC_ALPHA
        user32.UpdateLayeredWindow(self._hwnd, self._screen, ctypes.byref(self._pos), ctypes.byref(w.SIZE(self._w, self._h)),
                                   self._dc, ctypes.byref(w.POINT(0, 0)), 0, ctypes.byref(blend), 2)  # ULW_ALPHA
