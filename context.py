"""Where a dictation is going, to pick its cleanup style ("mode"): "ai" for prompts to an AI (IDEs, terminals,
AI chat sites and apps), "whatsapp" for personal messengers, "default" (the standard cleanup) for email, notes
and everything else. It goes by the app in front and, in a browser, by the site in the address bar. A
dictation can also start with "Hey Saylo, WhatsApp mode" (or AI prompt / email mode) to pick one for itself."""
import ctypes
import ctypes.wintypes as w
import logging
import re
import time
from pathlib import Path

log = logging.getLogger(__name__)

LABELS = {"ai": "AI prompt", "whatsapp": "WhatsApp", "default": ""}
SPOKEN = {"ai": "AI prompt mode", "whatsapp": "WhatsApp mode", "default": "email mode"}


def hints(mode: str) -> list[str]:
    """The commands worth offering in this mode: the other styles, and translating."""
    return [f"Hey Saylo, {SPOKEN[m]}" for m in ("ai", "whatsapp", "default") if m != mode] + \
           ["Hey Saylo, translate to English"]

APPS = {
    "ai": {
        # IDEs and code editors: their editor and AI chat panes look the same from outside, so both count.
        "code.exe", "code - insiders.exe", "cursor.exe", "windsurf.exe", "zed.exe", "devenv.exe", "trae.exe",
        "kiro.exe", "idea64.exe", "pycharm64.exe", "webstorm64.exe", "goland64.exe", "clion64.exe", "rider64.exe",
        "phpstorm64.exe", "rubymine64.exe", "datagrip64.exe", "rustrover64.exe", "studio64.exe",
        # Terminals, where Claude Code, Codex and Gemini CLI run.
        "windowsterminal.exe", "wt.exe", "powershell.exe", "pwsh.exe", "cmd.exe", "conhost.exe", "openconsole.exe",
        "alacritty.exe", "wezterm-gui.exe", "mintty.exe", "warp.exe", "tabby.exe", "hyper.exe",
        # AI desktop apps.
        "chatgpt.exe", "claude.exe", "perplexity.exe",
    },
    "whatsapp": {"whatsapp.exe", "whatsapp.root.exe", "telegram.exe", "signal.exe", "messenger.exe", "discord.exe"},
}
BROWSERS = {"chrome.exe", "msedge.exe", "brave.exe", "firefox.exe", "opera.exe", "vivaldi.exe", "arc.exe", "zen.exe"}
SITES = {
    "ai": ("chatgpt.com", "chat.openai.com", "claude.ai", "gemini.google.com", "aistudio.google.com", "perplexity.ai",
           "copilot.microsoft.com", "chat.deepseek.com", "grok.com", "chat.mistral.ai", "poe.com"),
    "whatsapp": ("web.whatsapp.com", "web.telegram.org", "messenger.com", "discord.com"),
}
# Only when the address bar can't be read (or for store apps, which all run as ApplicationFrameHost): words in
# the window title. ChatGPT names its tab after the chat, so it is often missed this way.
TITLES = {"ai": ("ChatGPT", " - Claude", "Gemini", "Perplexity"),
          "whatsapp": ("WhatsApp", "Telegram", "Messenger", "Discord")}

_user32 = ctypes.WinDLL("user32")
_kernel32 = ctypes.WinDLL("kernel32")
_user32.GetWindowThreadProcessId.argtypes = [w.HWND, ctypes.POINTER(w.DWORD)]
_user32.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, ctypes.c_int]
_kernel32.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
_kernel32.OpenProcess.restype = w.HANDLE
_kernel32.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
_kernel32.CloseHandle.argtypes = [w.HANDLE]


def _exe(hwnd: int) -> str:
    pid = w.DWORD()
    _user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    proc = _kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not proc:
        return ""
    try:
        buf, size = ctypes.create_unicode_buffer(1024), w.DWORD(1024)
        return Path(buf.value).name.lower() if _kernel32.QueryFullProcessImageNameW(proc, 0, buf, ctypes.byref(size)) else ""
    finally:
        _kernel32.CloseHandle(proc)


def _title(hwnd: int) -> str:
    buf = ctypes.create_unicode_buffer(512)
    _user32.GetWindowTextW(hwnd, buf, 512)
    return buf.value


def _site(hwnd: int) -> str:
    """The domain in a browser's address bar (its first text box), read through UI Automation; "" if unreadable."""
    try:
        import clr  # pythonnet, already loaded by pywebview
        for name in ("UIAutomationClient", "UIAutomationTypes"):  # .NET Framework ones need the full name
            clr.AddReference(f"{name}, Version=4.0.0.0, Culture=neutral, PublicKeyToken=31bf3856ad364e35")
        from System import IntPtr
        from System.Windows.Automation import AutomationElement, ControlType, PropertyCondition, TreeScope, ValuePattern
        root = AutomationElement.FromHandle(IntPtr(hwnd))
        box = root.FindFirst(TreeScope.Descendants, PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit))
        text = str(box.GetCurrentPropertyValue(ValuePattern.ValueProperty)) if box else ""
    except Exception as e:
        log.info("couldn't read the address bar: %s", e)
        return ""
    if " " in text.strip():  # a search being typed, not an address
        return ""
    return text.strip().split("://")[-1].split("/")[0].lower().removeprefix("www.")


def detect(hwnd: int) -> str:
    """The mode for the window being dictated into; "default" when unsure. Never raises."""
    t0 = time.perf_counter()
    exe = site = ""
    mode = "default"
    try:
        exe = _exe(hwnd)
        mode = next((m for m, apps in APPS.items() if exe in apps), "")
        if not mode and exe in BROWSERS:
            site = _site(hwnd)
            if site:
                mode = next((m for m, sites in SITES.items()
                             if any(site == s or site.endswith("." + s) for s in sites)), "default")
        if not mode and (exe in BROWSERS or exe == "applicationframehost.exe"):
            title = _title(hwnd)
            mode = next((m for m, words in TITLES.items() if any(x in title for x in words)), "")
        mode = mode or "default"
    except Exception:
        log.exception("couldn't tell which app is in front")
    log.info("context exe=%s site=%s mode=%s (%.0f ms)", exe, site, mode, (time.perf_counter() - t0) * 1000)
    return mode


# Commands to Saylo inside a dictation: a mode ("WhatsApp mode", "AI prompt style") and "translate to English".
# Two forms count, anywhere in the dictation:
# - a sentence starting with Saylo's name, then commands, then text: "Hey Saylo, WhatsApp mode, I'll be late"
# - sentences made only of command words, with Saylo's name among them: "Switch mode. Saylo. Use AI prompt mode."
# A sentence with any other words is text, so "my buddy Saylo" or "WhatsApp mode is great" is written as said.
# Saylo's name, plus what Whisper has written for it ("Shallow", "Hello"); these only count before a command.
_WAKE = (r"(?:saylo|say lo|say low|saylow|sailo|salo|sayla|silo|sylo|shailo|shylo|shilo|seylo|say hello"
         r"|shallow|hello|hallo|halo|solo|sello|shello)")
_MODE_WORDS = {"ai": r"ai prompt|eia prompt|prompt|ai|eia|coding",  # "EIA": Whisper's "AI"
               "whatsapp": r"whatsapp|whats app|what's app|chat|texting",
               "default": r"email|e-mail|mail|normal|default|notes?|standard"}
_PREFIX = re.compile(rf"(?:(?:hey|hi|ok|okay|hesh)[\s,.!]*)?{_WAKE}[\s,.!:;-]+", re.IGNORECASE)
_ATOM = re.compile(
    rf"(?:(?:(?:shift|switch|change|go|move)\s+(?:it\s+)?(?:to\s+)?|use\s+)?(?:the\s+)?"
    rf"(?P<mode>{'|'.join(_MODE_WORDS.values())})\s+(?:mode|style)\b"
    r"|(?P<translate>translate(?:\s+(?:it|this|everything|all))?(?:\s+(?:to|into|in))?\s+english|english\s+translation)\b)"
    r"[\s,.!:;-]*(?:(?:and|also|then)\s+)?", re.IGNORECASE)
_VOCAB = set("""hey hi ok okay please now saylo say lo low saylow sailo salo sayla silo sylo shailo shylo shilo seylo hello
    shallow hallo halo solo sello shello hesh
    switch shift change go move use to into in the a it this mode style translate translation english and also then on
    turn everything all whatsapp whats what's app chat texting ai eia prompt coding email e mail normal default note notes
    standard""".split())
_WORD_MODE = {"whatsapp": "whatsapp", "whats": "whatsapp", "chat": "whatsapp", "texting": "whatsapp", "ai": "ai",
              "eia": "ai", "prompt": "ai", "coding": "ai", "email": "default", "mail": "default", "normal": "default",
              "default": "default", "note": "default", "notes": "default", "standard": "default"}


def _words(text: str) -> list[str]:
    return re.findall(r"[a-z']+", text.lower())


def _mode_of(name: str) -> str:
    return next(k for k, pat in _MODE_WORDS.items() if re.fullmatch(pat, name, re.IGNORECASE))


def parse_command(raw: str) -> tuple[str | None, bool, str]:
    """(mode or None, translate, the text left to write) for the commands in a dictation."""
    sentences = re.split(r"(?<=[.!?])\s+", re.sub(r"\b[Aa]\.\s?[Ii]\.?", "AI", raw).strip())
    mode, translate, text = None, False, []
    i = 0
    while i < len(sentences):
        run = i
        while run < len(sentences) and (w := _words(sentences[run])) and all(x in _VOCAB for x in w):
            run += 1
        words = _words(" ".join(sentences[i:run]))
        named = re.search(rf"\b{_WAKE}\b", " ".join(words))
        run_mode = next((_WORD_MODE[x] for x in words if x in _WORD_MODE), None) if {"mode", "style"} & set(words) else None
        run_translate = "translate" in words or "translation" in words
        if run > i and named and (run_mode or run_translate):  # only command words, naming Saylo
            mode, translate, i = run_mode or mode, translate or run_translate, run
            continue
        sentence = sentences[i]
        if (p := _PREFIX.match(sentence)) and (a := _ATOM.match(sentence, p.end())):
            while a:  # "Hey Saylo, WhatsApp mode and translate to English, ..." then the text
                mode = _mode_of(a.group("mode")) if a.group("mode") else mode
                translate = translate or bool(a.group("translate"))
                end, a = a.end(), _ATOM.match(sentence, a.end())
            sentence = sentence[end:][:1].upper() + sentence[end:][1:]
        if sentence.strip():
            text.append(sentence.strip())
        i += 1
    rest = " ".join(text)
    return mode, translate, rest[:1].upper() + rest[1:]
