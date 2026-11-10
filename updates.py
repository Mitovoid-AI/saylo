"""Tells whether a newer Sayvra is out, from the latest GitHub release, and downloads its installer for an
in-app update. Release builds know their own version (config.VERSION); "dev" builds never get an update
offered. Never raises: no network means no update."""
import hashlib
import logging
import shutil
import threading
import time

import httpx

import config

log = logging.getLogger(__name__)

# The repo was called saylo before the rename to sayvra; GitHub redirects the old name to the new one.
LATEST_URL = "https://api.github.com/repos/Mitovoid-AI/saylo/releases/latest"
SOURCES = ("https://github.com/Mitovoid-AI/sayvra/releases/download/",  # installers are only taken from here
           "https://github.com/Mitovoid-AI/saylo/releases/download/")
SETUP_NAMES = ("Sayvra-Setup.exe", "Saylo-Setup.exe")  # the old name stays in releases until old copies have updated
UPDATES = config.APP_DIR / "updates"  # the downloaded installer; emptied at every start
CHECK_EVERY = 3600  # seconds between automatic checks; GitHub allows 60 anonymous requests an hour

# The latest release's installer as GitHub lists it (sha256 is GitHub's checksum of the file).
_last = {"at": 0.0, "version": "", "url": "", "size": 0, "sha256": ""}
# The download: idle, downloading, ready (verified, can be installed) or failed.
_job = {"state": "idle", "version": "", "done": 0, "total": 0, "error": ""}
_lock = threading.Lock()


def _parse(version: str) -> tuple[int, ...]:
    return tuple(int(x) for x in version.removeprefix("v").split("."))


def newer(latest: str, current: str) -> bool:
    """True when latest is a later version than current, compared as numbers (0.1.10 is after 0.1.9)."""
    try:
        return _parse(latest) > _parse(current)
    except ValueError:  # "dev", "" or anything else that isn't a version
        return False


def latest(force: bool = False) -> str:
    """The newest released version ("0.1.9"), or "" when GitHub can't be reached. Kept for CHECK_EVERY."""
    if force or time.time() - _last["at"] > CHECK_EVERY:
        try:
            r = httpx.get(LATEST_URL, headers={"Accept": "application/vnd.github+json"}, timeout=8)
            r.raise_for_status()
            release = r.json()
            assets = {a.get("name"): a for a in release.get("assets", [])}
            setup = next((assets[n] for n in SETUP_NAMES if n in assets), {})
            _last.update(at=time.time(), version=release["tag_name"].removeprefix("v"),
                         url=setup.get("browser_download_url", ""), size=setup.get("size", 0),
                         sha256=(setup.get("digest") or "").removeprefix("sha256:"))
        except (httpx.HTTPError, ValueError, KeyError) as e:
            log.info("update check failed: %s", e)
            return ""
    return _last["version"]


def check(force: bool = False) -> dict:
    found = latest(force)
    log.info("update check: running %s, latest %s", config.VERSION, found or "unknown")
    return {"current": config.VERSION, "latest": found, "available": newer(found, config.VERSION),
            "download": status()}


def status() -> dict:
    with _lock:
        return dict(_job)


def download() -> dict:
    """Starts downloading the latest release's installer in the background; follow it with status()."""
    with _lock:
        if _job["state"] in ("downloading", "ready") and _job["version"] == _last["version"]:
            return dict(_job)
        if not _last["url"].startswith(SOURCES):
            _job.update(state="failed", error="This release has no installer to download.")
            return dict(_job)
        _job.update(state="downloading", version=_last["version"], done=0, total=_last["size"], error="")
        threading.Thread(target=_download, args=(dict(_last),), daemon=True).start()
        return dict(_job)


def installer():
    """The downloaded, verified installer, or None."""
    with _lock:
        path = UPDATES / f"Sayvra-Setup-{_job['version']}.exe"
        return path if _job["state"] == "ready" and path.exists() else None


def _download(release: dict):
    path = UPDATES / f"Sayvra-Setup-{release['version']}.exe"
    part = path.with_suffix(".part")
    try:
        cleanup()
        UPDATES.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        with httpx.stream("GET", release["url"], follow_redirects=True, timeout=30) as r:
            r.raise_for_status()
            with open(part, "wb") as f:
                for chunk in r.iter_bytes(1 << 16):
                    f.write(chunk)
                    digest.update(chunk)
                    with _lock:
                        _job["done"] += len(chunk)
        size = part.stat().st_size
        if (release["size"] and size != release["size"]) or (release["sha256"] and digest.hexdigest() != release["sha256"]):
            raise ValueError(f"checksum mismatch ({size} bytes)")
        part.replace(path)
        log.info("update %s downloaded and verified (%d bytes)", release["version"], size)
        with _lock:
            _job["state"] = "ready"
    except Exception as e:
        log.warning("update download failed: %s", e)
        part.unlink(missing_ok=True)
        error = ("Check your internet connection." if isinstance(e, httpx.TransportError) else
                 "The file didn't match the release." if isinstance(e, ValueError) else "Something went wrong.")
        with _lock:
            _job.update(state="failed", error=error)


def cleanup():
    """Deletes downloaded installers (the update they carried is installed by now, or will be fetched again)."""
    shutil.rmtree(UPDATES, ignore_errors=True)
