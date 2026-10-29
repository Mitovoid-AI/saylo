"""Hold the hotkey, speak, release: hotkey -> recorder -> asr -> cleaner -> inserter."""
import logging
import queue
import threading
import time

import config
import pipeline
from hotkey import HoldHotkey
from recorder import Recorder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("dictation")

recorder = Recorder()
clips: queue.Queue[tuple[bytes, float, float]] = queue.Queue()


def on_down():
    recorder.start()
    log.info("Recording...")


def on_up():
    wav, seconds = recorder.stop()
    log.info("Clip %.2f s", seconds)
    clips.put((wav, seconds, time.perf_counter()))


def worker(hk: HoldHotkey):
    while True:
        wav, seconds, released_at = clips.get()
        try:
            r = pipeline.process(wav, seconds, released_at, hk.any_held)
        except Exception as e:
            log.error("Failed: %s", e)
            continue
        if r.skipped:
            log.info("Skipped: %s", r.skipped)
            continue
        log.info("ASR     %4.0f ms: %s", r.asr_ms, r.raw)
        log.info("Cleanup %4.0f ms: %s", r.clean_ms, r.text)
        log.info("Paste   %4.0f ms | total from release %4.0f ms", r.paste_ms, r.total_ms)


def main():
    config.require("GROQ_API_KEY", config.GROQ_API_KEY)
    config.cleanup_key()
    hk = HoldHotkey(config.HOTKEY, on_down, on_up)
    threading.Thread(target=worker, args=(hk,), daemon=True).start()
    hk.start()
    log.info("Ready. Hold %s and speak. Ctrl+C here to quit.", config.HOTKEY)
    try:
        hk.join()
    except KeyboardInterrupt:
        hk.stop()


if __name__ == "__main__":
    main()
