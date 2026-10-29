# Saylo

**Say it messy. Send it clean.** Hold a shortcut, talk the way you think, let go. Saylo turns your speech into tidy, punctuated text and types it where your cursor is.

- Drops filler words (um, uh, like, you know) and accidental repeats
- Keeps only your final version when you correct yourself ("Tuesday, no wait, Wednesday" → "Wednesday")
- Turns spoken lists ("first… second…") into numbered lists
- Never answers or rewrites what you said, it only cleans it up

**Website and downloads:** https://mitovoid-ai.github.io/saylo/

| Platform | How you dictate | Download |
|---|---|---|
| Windows 10 / 11 | Hold **Ctrl + Win** in any app | [Saylo-Setup.exe](https://github.com/Mitovoid-AI/saylo/releases/latest/download/Saylo-Setup.exe) · [portable Saylo.exe](https://github.com/Mitovoid-AI/saylo/releases/latest/download/Saylo.exe) |
| Chrome, Edge, Brave | Hold **Alt + Shift** in any text box on a website | [saylo-chrome.zip](https://github.com/Mitovoid-AI/saylo/releases/latest/download/saylo-chrome.zip) |
| Android | Hold the mic key on the Saylo keyboard | [saylo-android.apk](https://github.com/Mitovoid-AI/saylo/releases/latest/download/saylo-android.apk) |

All builds: [Releases](https://github.com/Mitovoid-AI/saylo/releases).

## You need a free Groq key (for now)

This version of Saylo uses [Groq](https://groq.com) for speech-to-text (Whisper) and cleanup, with your own API key:

1. Sign in at [console.groq.com/keys](https://console.groq.com/keys) and click **Create API Key**.
2. Paste it into Saylo's setup screen. It is stored only on your device.

Groq's free tier is enough for normal personal use. Its limits are per Groq account; if you hit one, Saylo tells you to wait a minute.

> **Coming in the next update: a paid version that needs no API key.** Sign in, pay, and dictate. No Groq account needed.

## Install

**Windows:** run `Saylo-Setup.exe`. If Windows shows "Windows protected your PC", click **More info → Run anyway** (the installer isn't code-signed yet). Saylo starts with Windows and waits in the tray; closing the window keeps it running. Uninstall from Settings → Apps.

**Chrome:** unzip `saylo-chrome.zip` into a folder you'll keep, open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked** and pick the folder. A setup page opens for your key and microphone. (A Chrome Web Store listing is planned.)

**Android:** install `saylo-android.apk` (allow "install unknown apps" when asked), open Saylo, and follow its three steps: add your key, allow the microphone, turn on the Saylo keyboard. In any app, switch to the Saylo keyboard, hold the mic and speak.

## Privacy

Saylo only records while you hold the shortcut or mic key. The audio goes straight from your device to Groq to be transcribed and cleaned; Saylo has no server of its own. On Windows, your dictation history is kept on your PC only, and you can turn it off or delete it.

## Repository layout

| Path | What it is |
|---|---|
| `app.py`, `ui/` | Windows app: pywebview window (onboarding, home, settings), tray, corner popup |
| `asr.py`, `cleaner.py`, `pipeline.py` | Speech → raw text (Groq Whisper) → clean text (LLM) → paste |
| `recorder.py`, `hotkey.py`, `inserter.py`, `store.py`, `tray.py` | Microphone, hold-to-talk hotkey, clipboard paste, local settings and history, tray icon |
| `build.ps1`, `installer.iss` | Builds `dist/Saylo.exe` (PyInstaller) and `dist/Saylo-Setup.exe` (Inno Setup) |
| `benchmark.py`, `main.py` | Cleanup model benchmark and a console runner |
| `extension/` | Chrome extension (Manifest V3) |
| `android/` | Android keyboard app (Kotlin) |
| `site/` | The website on GitHub Pages |
| `.github/workflows/release.yml` | Builds everything and publishes a release on every push to `main` |

## Build from source

**Windows app** (Python 3.14, Windows):

```powershell
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env      # add your GROQ_API_KEY
.venv\Scripts\python app.py # run it
.\build.ps1                 # build dist\Saylo.exe and, with Inno Setup 6 installed, dist\Saylo-Setup.exe
```

Local builds pack the `GROQ_API_KEY` from `.env` into the exe as a built-in key, so share those only with people you trust. Release builds made by GitHub Actions contain no key.

**Chrome extension:** load the `extension/` folder with **Load unpacked**.

**Android app** (JDK 17, Android SDK 36): `cd android` then `./gradlew assembleRelease`. Without signing secrets the APK is signed with the debug key.

## Releases

Every push to `main` runs `.github/workflows/release.yml`, which:

1. builds the Windows installer and exe, the Chrome zip and the Android APK,
2. publishes them as a GitHub Release named `v0.1.<run number>`,
3. deploys `site/` to GitHub Pages.

The website's download buttons always point to the latest release.
