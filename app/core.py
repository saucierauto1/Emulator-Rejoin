"""NovaHop Rejoin for Windows: accounts, launching Roblox, and the watcher.

Each account's NovaHop_Heartbeat.lua (in the executor's autoexec) writes
NovaHop/hb/<UserId>.txt = "time|ok" or "time|error|message|name" every 3 s.
The watcher reads those files and restarts only that account's Roblox window when
an error screen stays up, the heartbeat stops, or the window closes.
"""

import base64
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import psutil

BEE_SWARM = "1537690962"
ERROR_WAIT = 20      # error screen up this long -> restart (NovaHop gets a chance first)
STALE = 120          # no heartbeat this long -> restart
BOOT_WAIT = 180      # after a launch, give the window this long to load in (slow injectors need time)
GONE_WAIT = 10       # a tracked window that closed is relaunched after this long
RETRY = 900          # after 3 restarts that didn't bring it back, only retry this often
CHECK = 5
HOP_WAIT = 90        # teleport failed / server full: NovaHop retries itself, so wait longer before restarting
HOPPING = re.compile(r"teleport|experience is full|server is full|game is full|full server|unable to join|"
                     r"requested experience|joining", re.I)
ROBLOX_EXE = "robloxplayerbeta.exe"
HEARTBEAT_LINE = ('loadstring(game:HttpGet("https://gist.githubusercontent.com/saucierauto1/'
                  '4e760d58e39c190bb537f599d9d788a8/raw/NovaHop_Heartbeat.lua"))()')
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

IS_WIN = sys.platform == "win32"
DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "NovaHopRejoin")


# ------------------------------------------------------------------ encryption (Windows DPAPI)
# Sessions are encrypted with the Windows account's own key: only this Windows user on this PC
# can read them back.
if IS_WIN:
    import ctypes
    from ctypes import wintypes

    class _Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def _dpapi(data: bytes, protect: bool) -> bytes:
        buf = ctypes.create_string_buffer(data, len(data))
        src = _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
        dst = _Blob()
        fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
        if not fn(ctypes.byref(src), None, None, None, None, 0x1, ctypes.byref(dst)):
            raise OSError("Windows could not encrypt/decrypt the saved login")
        try:
            return ctypes.string_at(dst.pbData, dst.cbData)
        finally:
            ctypes.windll.kernel32.LocalFree(dst.pbData)
else:
    def _dpapi(data: bytes, protect: bool) -> bytes:   # only for testing off Windows
        return data


def protect(text: str) -> str:
    return base64.b64encode(_dpapi(text.encode(), True)).decode()


def unprotect(blob: str) -> str:
    return _dpapi(base64.b64decode(blob), False).decode()


# ------------------------------------------------------------------ settings and accounts on disk
def _path(name):
    os.makedirs(DATA_DIR, exist_ok=True)
    return os.path.join(DATA_DIR, name)


def _load(name, default):
    try:
        with open(_path(name), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save(name, data):
    tmp = _path(name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, _path(name))


class Store:
    """accounts.json: [{uid, name, display, cookie (encrypted), tracker, enabled}]"""

    def __init__(self):
        self.lock = threading.RLock()
        self.accounts = _load("accounts.json", [])
        self.settings = _load("settings.json", {})
        self.settings.setdefault("place", BEE_SWARM)
        self.settings.setdefault("ps_raw", "")
        self.settings.setdefault("launch_delay", 12)
        self.settings.setdefault("hb_dirs", [])
        for k, v in (("error_wait", ERROR_WAIT), ("stale", STALE), ("webhook", ""), ("wh_ping", ""),
                     ("wh_restart", True), ("wh_stuck", True), ("wh_login", True), ("wh_startstop", False),
                     ("autostart", False), ("on_top", False), ("text_color", "#FFFFFF"),
                     ("wh_status", True), ("status_every", 60), ("shots", True), ("status_msg", ""),
                     ("bg_color", "#000000"), ("btn_color", "#1A1A1A"), ("bg_image", ""), ("alpha", 1.0),
                     ("show_log", True), ("tray", True), ("update_check", True), ("launch_min", False), ("hop_wait", HOP_WAIT),
                     ("boot_wait", BOOT_WAIT)):
            self.settings.setdefault(k, v)

    def save(self):
        with self.lock:
            _save("accounts.json", self.accounts)
            _save("settings.json", self.settings)

    def get(self, uid):
        with self.lock:
            return next((a for a in self.accounts if a["uid"] == uid), None)

    def add(self, uid, name, display, cookie):
        with self.lock:
            a = self.get(uid)
            if a is None:
                a = {"uid": uid, "tracker": str(random.randint(10**11, 10**12 - 1)), "enabled": True}
                self.accounts.append(a)
            a.update(name=name, display=display, cookie=protect(cookie))
            self.save()
            return a

    def remove(self, uid):
        with self.lock:
            self.accounts = [a for a in self.accounts if a["uid"] != uid]
            self.save()

    def cookie(self, a):
        return unprotect(a["cookie"])


# ------------------------------------------------------------------ Roblox web calls
class RobloxError(Exception):
    pass


class LoginExpired(RobloxError):
    pass


def _request(url, cookie, method="GET", body=None, csrf=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Cookie", ".ROBLOSECURITY=" + cookie)
    req.add_header("User-Agent", UA)
    req.add_header("Referer", "https://www.roblox.com/")
    req.add_header("Origin", "https://www.roblox.com")
    req.add_header("RBXAuthenticationNegotiation", "1")
    if method == "POST":
        req.add_header("Content-Type", "application/json")
    if csrf:
        req.add_header("X-CSRF-TOKEN", csrf)
    return urllib.request.urlopen(req, timeout=20)


def _why(e):
    """Roblox's own error text from a failed request (never contains the login)"""
    try:
        raw = e.read().decode("utf-8", "ignore")
        d = json.loads(raw)
        errs = d.get("errors") or []
        if errs:
            return "; ".join(str(x.get("message", x)) for x in errs)[:200]
        return raw[:200]
    except Exception:
        return "no details"


def _post(url, cookie, body=None):
    """POST with Roblox's CSRF dance: the first try returns 403 + a token to retry with."""
    csrf = None
    for _ in range(2):
        try:
            return _request(url, cookie, "POST", body, csrf)
        except urllib.error.HTTPError as e:
            if e.code == 403 and e.headers.get("x-csrf-token") and not csrf:
                csrf = e.headers["x-csrf-token"]
                continue
            if e.code == 401:
                raise LoginExpired("login expired") from e
            raise RobloxError(f"Roblox said {e.code} for {url.split('/')[2]}: {_why(e)}") from e
    raise RobloxError("CSRF token was not accepted")


def whoami(cookie):
    try:
        with _request("https://users.roblox.com/v1/users/authenticated", cookie) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise LoginExpired("login expired") from e
        raise RobloxError(f"Roblox said {e.code}: {_why(e)}") from e
    return str(d["id"]), d.get("name", ""), d.get("displayName", "")


def auth_ticket(cookie):
    with _post("https://auth.roblox.com/v1/authentication-ticket", cookie) as r:
        t = r.headers.get("rbx-authentication-ticket")
    if not t:
        raise RobloxError("Roblox didn't give a launch ticket")
    return t


def parse_game(text):
    """Game link or place ID -> place ID ('' = Bee Swarm). None if not understood."""
    t = (text or "").strip()
    if not t:
        return BEE_SWARM
    m = re.search(r"games/(\d+)", t) or re.search(r"[?&]placeId=(\d+)", t)
    if m:
        return m.group(1)
    return t if t.isdigit() else None


def parse_ps(text):
    """Private server link -> ('code', linkCode) / ('share', code) / ('', ''). None if not understood."""
    t = (text or "").strip()
    if not t:
        return "", ""
    m = re.search(r"privateServerLinkCode=([A-Za-z0-9_-]+)", t)
    if m:
        return "code", m.group(1)
    m = re.search(r"[?&]code=([A-Za-z0-9_-]+)", t)
    if m:
        return "share", m.group(1)
    return None


def resolve_share(cookie, code):
    """roblox.com/share?code=...&type=Server -> (placeId, linkCode)"""
    with _post("https://apis.roblox.com/sharelinks/v1/resolve-link", cookie,
               {"linkId": code, "linkType": "Server"}) as r:
        d = json.load(r)
    inv = d.get("privateServerInviteData") or {}
    if not inv.get("linkCode"):
        raise RobloxError("That private server link didn't work (expired, or no access)")
    return str(inv.get("placeId") or ""), inv["linkCode"]


def launch_uri(ticket, tracker, place, link_code=""):
    if link_code:
        q = (f"request=RequestPrivateGame&placeId={place}&linkCode={link_code}"
             f"&browserTrackerId={tracker}&joinAttemptId={uuid.uuid4()}&joinAttemptOrigin=PlayButton")
    else:
        q = (f"request=RequestGame&browserTrackerId={tracker}&placeId={place}&isPlayTogetherGame=false"
             f"&joinAttemptId={uuid.uuid4()}&joinAttemptOrigin=PlayButton")
    launcher = "https://www.roblox.com/Game/PlaceLauncher.ashx?" + q
    return ("roblox-player:1+launchmode:play+gameinfo:" + ticket
            + "+launchtime:" + str(int(time.time() * 1000))
            + "+placelauncherurl:" + urllib.parse.quote(launcher, safe="")
            + "+browsertrackerid:" + tracker
            + "+robloxLocale:en_us+gameLocale:en_us+channel:+LaunchExp:InApp")


def server_for(a, s):
    """(place, private server link) for an account: its own if set, else the main Game / server setting"""
    if a.get("own_server"):
        return a.get("place") or BEE_SWARM, a.get("ps_raw", "")
    return s["place"], s.get("ps_raw", "")


def game_name(place):
    return "Bee Swarm Simulator" if place == BEE_SWARM else f"Place {place}"


# ------------------------------------------------------------------ updates
REPO = "saucierauto1/Windows-Rejoiner"


def latest_version():
    """newest release number on GitHub (tags look like win-v1.0.12), or None"""
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/latest",
                                 headers={"User-Agent": "NovaHopRejoin", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        tag = json.load(r).get("tag_name", "")
    m = re.search(r"(\d+)$", tag)
    return int(m.group(1)) if m else None


# ------------------------------------------------------------------ Discord webhook
def is_webhook(url):
    return bool(re.match(r"^https://(ptb\.|canary\.)?discord(app)?\.com/api/webhooks/\d+/[\w-]+$", (url or "").strip()))


def send_webhook(url, text):
    data = json.dumps({"content": text[:1900], "username": "NovaHop Rejoin"}).encode()
    req = urllib.request.Request(url.strip(), data=data, method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": "NovaHopRejoin/1.0"})
    urllib.request.urlopen(req, timeout=10).close()


def send_status(url, embed, jpg=None):
    """Post the status message (optionally with a screenshot). Returns the new message id."""
    boundary = "----nhr%d" % int(time.time() * 1000)
    payload = json.dumps({"username": "NovaHop Rejoin", "embeds": [embed]}).encode()
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
            f"Content-Type: application/json\r\n\r\n").encode() + payload + b"\r\n"
    if jpg:
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"screen.jpg\"\r\n"
                 f"Content-Type: image/jpeg\r\n\r\n").encode() + jpg + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(url.strip() + "?wait=true", data=body, method="POST",
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                                          "User-Agent": "NovaHopRejoin/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return str(json.load(r).get("id", ""))


def delete_message(url, mid):
    req = urllib.request.Request(f"{url.strip()}/messages/{mid}", method="DELETE",
                                 headers={"User-Agent": "NovaHopRejoin/1.0"})
    urllib.request.urlopen(req, timeout=15).close()


def screenshot_jpg():
    try:
        from PIL import ImageGrab
        import io
        img = ImageGrab.grab()
        if img.width > 1280:
            img = img.resize((1280, img.height * 1280 // img.width))
        buf = io.BytesIO()
        img.convert("RGB").save(buf, "JPEG", quality=70)
        return buf.getvalue()
    except Exception:
        return None


# ------------------------------------------------------------------ several Roblox windows at once
_mutex = None


def allow_multi():
    """Holding Roblox's single-instance mutex lets several Roblox windows run side by side.
    Returns False when Roblox was already open (then it only works after closing Roblox)."""
    global _mutex
    if not IS_WIN or _mutex:
        return True
    import ctypes
    _mutex = ctypes.windll.kernel32.CreateMutexW(None, True, "ROBLOX_singletonMutex")
    return ctypes.windll.kernel32.GetLastError() != 183   # 183 = already existed


# ------------------------------------------------------------------ heartbeat folders
def find_heartbeat_dirs():
    """Every NovaHop/hb folder an executor workspace has, plus its autoexec folder(s)."""
    home = os.path.expanduser("~")
    roots = [(home, 5)]
    for env in ("LOCALAPPDATA", "APPDATA"):
        if os.environ.get(env):
            roots.append((os.environ[env], 3))
    if IS_WIN:
        for d in ("C:\\", "D:\\"):
            if os.path.isdir(d):
                roots.append((d, 2))
    skip = {"windows", "program files", "program files (x86)", "programdata", "$recycle.bin", "node_modules",
            ".git", "packages", "microsoft", "google", "mozilla", "temp", "cache", "steamapps",
            "system volume information", "appdata"}
    workspaces, autoexecs = set(), set()

    def walk(path, depth):
        try:
            it = list(os.scandir(path))
        except OSError:
            return
        names = {e.name.lower(): e for e in it if e.is_dir(follow_symlinks=False)}
        for low, e in names.items():
            if low == "workspace":
                workspaces.add(e.path)
            if low.startswith("autoexec") or low in ("auto-exec", "autorun"):
                autoexecs.add(e.path)
        if depth <= 0:
            return
        for low, e in names.items():
            if low in skip or low.startswith("."):
                continue
            walk(e.path, depth - 1)

    for root, depth in roots:
        walk(root, depth)
    hb = []
    for w in sorted(workspaces):
        p = os.path.join(w, "NovaHop", "hb")
        if os.path.isdir(p):
            hb.append(p)
    return hb, sorted(workspaces), sorted(autoexecs)


def install_heartbeat(autoexecs):
    done = []
    for d in autoexecs:
        try:
            if any("NovaHop_Heartbeat" in open(os.path.join(d, f), encoding="utf-8", errors="ignore").read()
                   for f in os.listdir(d) if os.path.isfile(os.path.join(d, f))):
                done.append(d)
                continue
            with open(os.path.join(d, "heartbeat.lua"), "w", encoding="utf-8") as f:
                f.write(HEARTBEAT_LINE + "\n")
            done.append(d)
        except OSError:
            pass
    return done


def read_heartbeats(dirs):
    """newest line per UserId across all folders -> {uid: (ts, state, msg, name)}"""
    best = {}
    for d in dirs:
        try:
            files = os.listdir(d)
        except OSError:
            continue
        for f in files:
            if not f.endswith(".txt") or not f[:-4].isdigit():
                continue
            try:
                with open(os.path.join(d, f), encoding="utf-8", errors="ignore") as fh:
                    parts = fh.read().strip().split("|")
                ts = int(parts[0])
            except (OSError, ValueError, IndexError):
                continue
            uid = f[:-4]
            if uid not in best or ts > best[uid][0]:
                best[uid] = (ts, parts[1] if len(parts) > 1 else "", parts[2] if len(parts) > 2 else "",
                             parts[3] if len(parts) > 3 else "")
    return best


# ------------------------------------------------------------------ Roblox windows (processes)
def roblox_processes():
    out = []
    for p in psutil.process_iter(["pid", "name", "cmdline", "create_time"]):
        try:
            if (p.info["name"] or "").lower() == ROBLOX_EXE:
                out.append((p.info["pid"], " ".join(p.info["cmdline"] or []), p.info["create_time"] or 0))
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return out


def minimize_pid(pid):
    """minimize a process's windows without focusing them (stops new Roblox windows stealing focus)"""
    if not IS_WIN:
        return

    def run():
        import ctypes
        from ctypes import wintypes
        u = ctypes.windll.user32
        found = []
        proto = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def each(hwnd, _):
            owner = wintypes.DWORD()
            u.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
            if owner.value == pid and u.IsWindowVisible(hwnd):
                found.append(hwnd)
            return True
        cb = proto(each)
        for _ in range(40):
            found.clear()
            u.EnumWindows(cb, 0)
            if found:
                for h in found:
                    u.ShowWindow(h, 7)      # SW_SHOWMINNOACTIVE
                return
            time.sleep(0.5)
    threading.Thread(target=run, daemon=True).start()


def kill(pid):
    try:
        psutil.Process(pid).kill()
    except psutil.Error:
        pass


def open_uri(uri):
    if IS_WIN:
        os.startfile(uri)
    else:
        raise RobloxError("Launching Roblox only works on Windows")


# ------------------------------------------------------------------ the watcher
class Watcher:
    def __init__(self, store, log):
        self.store = store
        self.log = log
        self.alive = False
        self.thread = None
        self.pids = {}        # uid -> pid we launched
        self.last = {}        # uid -> time of last launch
        self.err_since = {}
        self.gone_since = {}
        self.fails = {}
        self.restarts = {}
        self.status = {}      # uid -> text for the UI
        self.next_launch = 0.0
        self.queue = []       # uids waiting for their launch slot
        # hooks (swapped out in tests)
        self.procs = roblox_processes
        self.kill = kill
        self.open = open_uri
        self.now = time.time
        self.ticket = auth_ticket
        self.resolve = resolve_share

    # -------------------------------------------------- control
    def start(self):
        if self.alive:
            return
        self.alive = True
        if not allow_multi():
            self.log("Roblox was already open, so only one window may work. Close all Roblox windows, then Stop and Start.")
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        threading.Thread(target=self._report_loop, daemon=True).start()

    def stop(self):
        self.alive = False
        self.queue.clear()
        self.log("Watcher stopped")
        self.notify("startstop", "Watcher stopped")

    def _run(self):
        self.log("Watcher started")
        self.notify("startstop", f"Watcher started ({len(self.store.accounts)} accounts)")
        if not self.store.settings["hb_dirs"]:
            self.rescan()
        while self.alive:
            try:
                self.tick()
            except Exception as e:      # never let one bad loop kill the watcher
                self.log(f"Problem in watcher loop: {e}")
            for _ in range(CHECK * 10):
                if not self.alive:
                    break
                time.sleep(0.1)

    def rescan(self):
        hb, ws, ax = find_heartbeat_dirs()
        added = install_heartbeat(ax)
        for d in added:
            self.log(f"Heartbeat is in {d}")
        known = self.store.settings["hb_dirs"]
        for d in hb:
            if d not in known:
                known.append(d)
                self.log(f"Watching {d}")
        if not known:
            self.log("No NovaHop/hb folder found yet. Join the game once with the executor attached, "
                     "or pick the executor's workspace folder in Folders.")
        self.store.save()
        return hb, ws, ax

    # -------------------------------------------------- one account's window
    def find_pid(self, a, procs):
        uid = a["uid"]
        pid = self.pids.get(uid)
        if pid and any(p[0] == pid for p in procs):
            return pid
        for p in procs:   # launched by us earlier, maybe before the app restarted
            if a["tracker"] in p[1]:
                self.pids[uid] = p[0]
                return p[0]
        # Roblox often hands over to a second process while starting, and its anti-cheat can hide the
        # details above: adopt the first unclaimed Roblox window that started after this account's launch
        last = self.last.get(uid)
        if last:
            claimed = {v for k, v in self.pids.items() if k != uid}
            cands = sorted((p for p in procs if p[0] not in claimed and last - 5 <= p[2] <= last + 180),
                           key=lambda p: p[2])
            if cands:
                self.pids[uid] = cands[0][0]
                return cands[0][0]
        return None

    def too_many_windows(self, procs):
        """never run more Roblox windows than there are enabled accounts"""
        return len(procs) >= sum(1 for a in self.store.accounts if a.get("enabled", True))

    def boot_wait(self):
        return max(60, int(self.store.settings.get("boot_wait", BOOT_WAIT)))

    def launch(self, a, why):
        uid = a["uid"]
        s = self.store.settings
        try:
            cookie = self.store.cookie(a)
            place, ps_raw = server_for(a, s)
            link = ""
            kind, code = parse_ps(ps_raw) or ("", "")
            if kind == "code":
                link = code
            elif kind == "share":
                p2, link = self.resolve(cookie, code)
                place = p2 or place
            uri = launch_uri(self.ticket(cookie), a["tracker"], place, link)
        except LoginExpired:
            self.status[uid] = "login expired, log in again"
            self.log(f"{a['name']}: login expired. Remove the account and add it again.")
            self.notify("login", f"**{a['name']}**: login expired, log in again in the app")
            a["enabled"] = False
            self.store.save()
            return
        except Exception as e:
            self.status[uid] = "launch failed"
            self.log(f"{a['name']}: couldn't launch ({e})")
            self.last[uid] = self.now() - self.boot_wait() + 30   # try again in ~30 s
            return
        before_procs = self.procs()
        before = {p[0] for p in before_procs}
        if why in ("Roblox window closed", "not running", "launch all") and self.too_many_windows(before_procs):
            self.status[uid] = "not opened: already as many roblox windows as accounts"
            self.log(f"{a['name']}: not opening another Roblox, there are already {len(before_procs)} open "
                     "(close the extra ones, or use Launch to force it)")
            self.last[uid] = self.now()
            return
        self.log(f"Launching {a['name']}: {why}")
        self.open(uri)
        self.last[uid] = self.now()
        self.err_since[uid] = 0
        self.gone_since.pop(uid, None)
        self.status[uid] = "launching"
        # remember the new window: matched by our tracker id, or the one new window that appeared
        threading.Thread(target=self._grab_pid, args=(a, before), daemon=True).start()

    def _grab_pid(self, a, before):
        for _ in range(60):
            time.sleep(0.5)
            procs = self.procs()
            for p in procs:
                if a["tracker"] in p[1]:
                    self.pids[a["uid"]] = p[0]
                    return
            mine = set(self.pids.values())
            new = [p[0] for p in procs if p[0] not in before and p[0] not in mine]
            if len(new) == 1:
                self.pids[a["uid"]] = new[0]
                break
        else:
            return
        if self.store.settings.get("launch_min"):
            minimize_pid(self.pids[a["uid"]])

    def notify(self, kind, text):
        s = self.store.settings
        url = s.get("webhook", "")
        if not is_webhook(url) or not s.get("wh_" + kind, True):
            return
        ping = s.get("wh_ping", "").strip()
        if ping and kind in ("stuck", "login"):
            text = f"<@{ping}> " + text

        def go():
            try:
                send_webhook(url, text)
            except Exception as e:
                self.log(f"Discord webhook failed: {e}")
        threading.Thread(target=go, daemon=True).start()

    # -------------------------------------------------- live Discord status message
    def _report_loop(self):
        for _ in range(200):          # first update ~20 s after start
            if not self.alive:
                return
            time.sleep(0.1)
        while self.alive:
            s = self.store.settings
            if is_webhook(s.get("webhook", "")) and s.get("wh_status", True):
                try:
                    self.post_status()
                except Exception as e:
                    self.log(f"Discord status failed: {e}")
            for _ in range(max(30, int(s.get("status_every", 60))) * 10):
                if not self.alive:
                    return
                time.sleep(0.1)

    def status_embed(self):
        lines, online = [], 0
        for a in self.store.accounts:
            st = self.status.get(a["uid"], "not started") if a.get("enabled", True) else "off"
            if st == "OK":
                dot, word = "\U0001F7E2", "Online"; online += 1
            elif st.startswith(("loading", "starting", "launching", "waiting", "window closed")):
                dot, word = "\U0001F7E1", st[:1].upper() + st[1:]
            elif st in ("off", "not started"):
                dot, word = "\u26AB", st[:1].upper() + st[1:]
            else:
                dot, word = "\U0001F534", st[:1].upper() + st[1:]
            ram = "\u2013"
            pid = self.pids.get(a["uid"])
            if pid:
                try:
                    ram = f"{psutil.Process(pid).memory_info().rss // (1024 * 1024)} MB"
                except psutil.Error:
                    pass
            name = a.get("nick") or a["name"]
            game = game_name(server_for(a, self.store.settings)[0])
            lines.append(f"{dot} **{name}** \u2014 {word}\nGame: {game} \u00B7 Rejoins: {self.restarts.get(a['uid'], 0)} \u00B7 RAM: {ram}")
        vm = psutil.virtual_memory()
        cpu = psutil.cpu_percent(interval=1)
        return {
            "title": f"NovaHop Rejoin \u00B7 {online}/{len(self.store.accounts)} online",
            "description": ("\n\n".join(lines) or "No accounts yet.")[:3900],
            "color": 0x7C3AED,
            "fields": [{"name": "PC", "value": f"CPU {cpu:.0f}% \u00B7 RAM {vm.used / 1024**3:.1f} / {vm.total / 1024**3:.1f} GB",
                        "inline": False}],
            "footer": {"text": f"Updates every {max(30, int(self.store.settings.get('status_every', 60)))}s"},
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

    def post_status(self):
        s = self.store.settings
        url = s["webhook"]
        embed = self.status_embed()
        jpg = screenshot_jpg() if s.get("shots", True) else None
        if jpg:
            embed["image"] = {"url": "attachment://screen.jpg"}
        new = send_status(url, embed, jpg)
        old = s.get("status_msg", "")
        if old and old != new:
            try:
                delete_message(url, old)
            except Exception:
                pass
        s["status_msg"] = new
        self.store.save()

    def restart(self, a, pid, why):
        uid = a["uid"]
        if pid:
            self.kill(pid)
        self.pids.pop(uid, None)
        self.restarts[uid] = self.restarts.get(uid, 0) + 1
        self.notify("restart", f"Restarting **{a['name']}**: {why} (restart #{self.restarts[uid]})")
        self.want_launch(a, why)

    def want_launch(self, a, why):
        if a["uid"] not in [q[0] for q in self.queue]:
            self.queue.append((a["uid"], why))
            self.status[a["uid"]] = "waiting to launch"

    def launch_now(self, uid):
        """the Launch button on one account: queued while watching, straight away otherwise"""
        a = self.store.get(uid)
        if not a:
            return
        pid = self.find_pid(a, self.procs())
        if self.alive:
            self.restart(a, pid, "launched by hand")
            return

        def go():
            if pid:
                self.kill(pid)
                self.pids.pop(uid, None)
                time.sleep(2)
            self.launch(a, "launched by hand")
        threading.Thread(target=go, daemon=True).start()

    def launch_all(self):
        """open every enabled account that isn't already open, one at a time"""
        def go():
            todo = [a for a in self.store.accounts if a.get("enabled", True)]
            for i, a in enumerate(todo):
                if self.find_pid(a, self.procs()):
                    continue
                if self.alive:
                    self.want_launch(a, "launch all")
                    continue
                self.launch(a, "launch all")
                if i < len(todo) - 1:
                    time.sleep(max(5, int(self.store.settings.get("launch_delay", 12))))
            self.log("launch all: done" if not self.alive else "launch all: queued")
        threading.Thread(target=go, daemon=True).start()

    def close(self, uid):
        a = self.store.get(uid)
        pid = self.find_pid(a, self.procs()) if a else None
        if pid:
            self.kill(pid)
            self.pids.pop(uid, None)
            self.log(f"closed {a['name']}")
        elif a:
            self.log(f"{a['name']} isn't open (or wasn't launched by this app)")

    def close_all(self):
        for a in self.store.accounts:
            pid = self.find_pid(a, self.procs())
            if pid:
                self.kill(pid)
                self.pids.pop(a["uid"], None)
        self.log("closed all accounts launched by this app")

    # -------------------------------------------------- one pass over every account
    def tick(self):
        now = self.now()
        procs = self.procs()
        err_wait = int(self.store.settings.get("error_wait", ERROR_WAIT))
        stale = int(self.store.settings.get("stale", STALE))
        beats = read_heartbeats(self.store.settings["hb_dirs"])
        for a in list(self.store.accounts):
            uid = a["uid"]
            if not a.get("enabled", True):
                self.status[uid] = "off"
                continue
            if uid in [q[0] for q in self.queue]:
                continue
            pid = self.find_pid(a, procs)
            hb = beats.get(uid)
            age = now - hb[0] if hb else None
            last = self.last.get(uid)
            booting = last is not None and now - last < self.boot_wait()
            f = self.fails.get(uid, 0)

            # a fresh heartbeat = the account is in the game, even if we lost track of its window
            fresh = hb is not None and age < 30
            if pid is None and not fresh:
                # no Roblox window for this account and no recent heartbeat: it isn't running
                if booting and now - last < 90:
                    self.status[uid] = f"starting ({int(now - last)}s)"
                    continue
                if last is None:
                    self.want_launch(a, "not running")
                    continue
                gone = self.gone_since.setdefault(uid, now)
                if now - gone < GONE_WAIT:
                    self.status[uid] = "window closed"
                    continue
                if f >= 3 and now - last < RETRY:
                    self.status[uid] = "not coming back, retrying every 15 min"
                    continue
                if f == 2:
                    self.notify("stuck", f"**{a['name']}** keeps closing after 3 relaunches. Check that PC.")
                self.fails[uid] = f + 1
                self.restart(a, None, "Roblox window closed")
                continue
            self.gone_since.pop(uid, None)

            if booting:
                self.status[uid] = f"loading ({int(now - last)}s)"
                if hb and age < 20 and hb[1] != "error":
                    self.fails[uid] = 0
                continue

            if hb and age < stale and hb[1] == "error":
                self.status[uid] = "error: " + hb[2]
                if not self.err_since.get(uid):
                    self.err_since[uid] = now
                    self.log(f"{a['name']}: error on screen: {hb[2]}")
                # a teleport / full-server error is NovaHop mid-hop and it retries by itself: give it longer
                wait = int(self.store.settings.get("hop_wait", HOP_WAIT)) if HOPPING.search(hb[2]) else err_wait
                if now - self.err_since[uid] >= wait:
                    self.restart(a, pid, f"error screen ({hb[2]})")
                continue
            if self.err_since.get(uid):
                self.log(f"{a['name']}: error cleared")
            self.err_since[uid] = 0

            if hb is None or age >= stale:
                if f >= 3 and last and now - last < RETRY:
                    self.status[uid] = "not coming back, retrying every 15 min"
                    continue
                if f == 2:
                    self.notify("stuck", f"**{a['name']}** didn't come back after 3 restarts. Check that PC.")
                    self.log(f"{a['name']} didn't come back after 3 restarts. Is the heartbeat in the executor's "
                             "autoexec, and is the executor attaching? Retrying every 15 min.")
                self.fails[uid] = f + 1
                self.restart(a, pid, "no heartbeat for 2 min" if hb else "no heartbeat yet")
            else:
                self.fails[uid] = 0
                self.status[uid] = "OK"

        # launch one waiting account per slot, so Roblox isn't flooded
        if self.queue and now >= self.next_launch:
            uid, why = self.queue.pop(0)
            a = self.store.get(uid)
            if a and a.get("enabled", True):
                self.launch(a, why)
            self.next_launch = now + max(5, int(self.store.settings.get("launch_delay", 12)))
