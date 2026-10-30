"""Mic capture into memory, returned as 16 kHz mono 16-bit WAV bytes."""
import array
import io
import threading
import wave

import lameenc
import sounddevice as sd

from config import SAMPLE_RATE


def list_input_devices(rescan: bool = False) -> list[tuple[str, str]]:
    """Returns (device_name, display_name) for each mic, using the default host API (MME on Windows:
    fastest to start, resamples to 16 kHz). MME cuts names at 31 chars, so the full name is borrowed
    from another host API's entry for the same device when one matches. Rescan only when no stream is open."""
    if rescan:
        sd._terminate()
        sd._initialize()
    devices = sd.query_devices()
    host = sd.query_devices(kind="input")["hostapi"]
    full_names = [d["name"] for d in devices if d["max_input_channels"] > 0 and d["hostapi"] != host]
    out = []
    for i, d in enumerate(devices):
        # "Sound Mapper" is MME's alias for the system default, offered separately as "System default".
        if d["hostapi"] != host or d["max_input_channels"] == 0 or d["name"].startswith("Microsoft Sound Mapper"):
            continue
        display = next((f for f in full_names if f.startswith(d["name"]) and "@System32" not in f), d["name"])
        out.append((d["name"], display))
    return out


def is_bluetooth(name: str) -> bool:
    """Windows exposes Bluetooth hands-free mics through the bthhfenum driver; its WDM-KS entry
    carries that driver path plus the same "(Device)" label as the short MME name."""
    label = name[name.find("("):] if "(" in name else name
    return any("bthhfenum" in d["name"] and label in d["name"] for d in sd.query_devices())


def resolve_device(name: str | None) -> int | None:
    """Device index for a saved mic name, or None (system default) if it isn't connected."""
    if not name:
        return None
    host = sd.query_devices(kind="input")["hostapi"]
    for i, d in enumerate(sd.query_devices()):
        if d["hostapi"] == host and d["max_input_channels"] > 0 and d["name"] == name:
            return i
    return None


class Recorder:
    def __init__(self, sample_rate: int = SAMPLE_RATE):
        self.sample_rate = sample_rate
        self._chunks: list[bytes] = []
        self._lock = threading.Lock()
        self._stream: sd.RawInputStream | None = None
        self.level = 0  # loudness of the latest chunk, 0-100

    def _callback(self, data, frames, time_info, status):
        chunk = bytes(data)
        self.level = level(chunk)
        with self._lock:
            self._chunks.append(chunk)

    def start(self, device: int | None = None):
        self._chunks = []
        self.level = 0
        self._stream = sd.RawInputStream(
            device=device, samplerate=self.sample_rate, channels=1, dtype="int16", callback=self._callback
        )
        self._stream.start()

    def stop(self) -> tuple[bytes, float]:
        """Stops recording; returns (wav_bytes, seconds)."""
        if self._stream is None:
            return b"", 0.0
        self._stream.stop()
        self._stream.close()
        self._stream = None
        with self._lock:
            pcm = b"".join(self._chunks)
        seconds = len(pcm) / 2 / self.sample_rate
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.sample_rate)
            w.writeframes(pcm)
        return buf.getvalue(), seconds


def speech_ms(wav: bytes) -> int:
    """Milliseconds of the clip that are clearly louder than the clip's own background (30 ms frames).
    Silence, steady room noise and single clicks score near 0; Whisper turns those into "Thank you."
    or "you", so the pipeline skips them before calling it."""
    with wave.open(io.BytesIO(wav)) as w:
        pcm = array.array("h", w.readframes(w.getnframes()))
    n = SAMPLE_RATE * 30 // 1000
    # Leading zeros while a Bluetooth mic wakes up are not background, so all-zero frames are left out.
    peaks = sorted(p for i in range(0, len(pcm) - n + 1, n) if (p := max(-min(pcm[i:i + n]), max(pcm[i:i + n]))))
    if not peaks:
        return 0
    background = peaks[len(peaks) // 5]
    return 30 * sum(p > max(3 * background, 300) for p in peaks)


def level(chunk: bytes) -> int:
    """Loudness of one mic chunk, 0-100, for the listening pill's bars."""
    samples = array.array("h", chunk)
    return min(100, max(-min(samples, default=0), max(samples, default=0)) // 120)


def to_mp3(wav: bytes) -> bytes:
    """Compresses a clip for keeping in the history: 32 kbps mono, about 8x smaller than the WAV."""
    with wave.open(io.BytesIO(wav)) as w:
        rate, pcm = w.getframerate(), w.readframes(w.getnframes())
    enc = lameenc.Encoder()
    enc.set_bit_rate(32)
    enc.set_in_sample_rate(rate)
    enc.set_channels(1)
    enc.set_quality(5)
    return bytes(enc.encode(pcm) + enc.flush())
