"""System-tray icon and single-instance guard, so Saylo keeps listening after its window is closed.

Only one Saylo may run (two would paste every dictation twice). The first instance listens on a
localhost port; a second launch sends it "show" (or "quit") and exits."""
import logging
import socket
import threading

import pystray
from PIL import Image, ImageDraw

log = logging.getLogger(__name__)
INSTANCE_PORT = 47615


def signal_running(command: str = "show") -> bool:
    """True if another Saylo is running (and it received the command)."""
    try:
        with socket.create_connection(("127.0.0.1", INSTANCE_PORT), timeout=0.5) as s:
            s.sendall(command.encode())
            return True
    except OSError:
        return False


def serve_commands(handlers: dict) -> None:
    """Accepts "show" / "quit" from later launches. If the port is taken, the guard is skipped."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        srv.bind(("127.0.0.1", INSTANCE_PORT))
    except OSError as e:
        log.warning("single-instance port %s unavailable: %s", INSTANCE_PORT, e)
        return
    srv.listen()

    def loop():
        while True:
            conn, _ = srv.accept()
            with conn:
                cmd = conn.recv(16).decode(errors="ignore")
            if cmd in handlers:
                handlers[cmd]()

    threading.Thread(target=loop, daemon=True).start()


def icon_image(size: int = 64) -> Image.Image:
    """The Saylo mark: dark rounded square with mint sound bars (layout in 64-unit coordinates)."""
    k = size / 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=18 * k, fill="#16141B")
    r = 3 * k
    for x, top, bottom in ((16, 30, 40), (26, 20, 44), (36, 14, 50), (46, 24, 40)):
        x, top, bottom = x * k, top * k, bottom * k
        d.rectangle((x - r, top, x + r, bottom), fill="#7FE0C2")
        d.ellipse((x - r, top - r, x + r, top + r), fill="#7FE0C2")
        d.ellipse((x - r, bottom - r, x + r, bottom + r), fill="#7FE0C2")
    return img


def start_tray(on_open, on_quit) -> pystray.Icon:
    icon = pystray.Icon("Saylo", icon_image(),"Saylo: hold Ctrl + Win to dictate", menu=pystray.Menu(
        pystray.MenuItem("Open Saylo", lambda: on_open(), default=True),
        pystray.MenuItem("Quit Saylo", lambda: on_quit()),
    ))
    icon.run_detached()
    return icon
