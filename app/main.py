"""NovaHop Rejoin for Windows - the app window.

Run with no arguments for the app. "--login <file>" opens the Roblox login window (used by
"Add account"; it runs as its own process because the browser window needs the main thread).
"""

import json
import os
import subprocess
import sys
import tempfile
import threading
import time

import core


# ------------------------------------------------------------------ login window (child process)
def login_mode(out_file):
    import webview

    w = webview.create_window("Log in to Roblox - roblox.com", "https://www.roblox.com/login",
                              width=520, height=760)

    READ_USER = ("(function(){var m=document.querySelector('meta[name=\"user-data\"]');"
                 "return m?JSON.stringify({id:m.getAttribute('data-userid'),name:m.getAttribute('data-name'),"
                 "display:m.getAttribute('data-displayname')}):null})()")

    def wait_for_login():
        cookie, since = None, 0
        while True:
            time.sleep(1.0)
            try:
                for c in w.get_cookies() or []:
                    for key, morsel in c.items():
                        if key == ".ROBLOSECURITY" and morsel.value:
                            if morsel.value != cookie:
                                cookie, since = morsel.value, time.time()
                if not cookie:
                    continue
                url = w.get_current_url() or ""
                # wait until Roblox has finished logging in and shows a normal page, then read who it is
                if "/login" in url or "/challenge" in url:
                    continue
                user = None
                try:
                    raw = w.evaluate_js(READ_USER)
                    user = json.loads(raw) if raw else None
                except Exception:
                    user = None
                if not (user and user.get("id")) and time.time() - since < 15:
                    continue
                with open(out_file, "w", encoding="utf-8") as f:
                    json.dump({"cookie": core.protect(cookie), "user": user or {}}, f)   # encrypted on disk
                w.destroy()
                return
            except Exception:
                continue

    webview.start(wait_for_login, private_mode=True)   # private: every login starts logged out
    os._exit(0)


# ------------------------------------------------------------------ app window
def app_mode():
    import tkinter as tk
    from tkinter import filedialog, messagebox

    FONT = ("Consolas", 10)
    DISCORD = "https://discord.gg/brfJ2ZtegE"

    store = core.Store()
    BG = store.settings.get("bg_color", "#000000")
    BTN = store.settings.get("btn_color", "#1A1A1A")
    DIM = "#888888"
    T = {"fg": store.settings.get("text_color", "#FFFFFF")}
    lines = []
    lock = threading.Lock()
    count = [0]

    def log(msg):
        with lock:
            count[0] += 1
            lines.append((count[0], time.strftime("[%H:%M:%S] ") + msg))
            del lines[:-300]

    watcher = core.Watcher(store, log)

    root = tk.Tk()
    root.title("NovaHop Rejoin")
    root.geometry("760x640")
    root.configure(bg=BG)
    try:
        root.attributes("-alpha", max(0.4, min(1.0, float(store.settings.get("alpha", 1.0)))))
    except Exception:
        pass

    # background picture (drawn on the main canvas below)
    bg_src = None
    bg_path = store.settings.get("bg_image", "")
    if bg_path and not os.path.exists(bg_path):
        log(f"background picture is missing ({bg_path}), pick it again in Look")
    elif bg_path:
        try:
            from PIL import Image
            bg_src = Image.open(bg_path).convert("RGB")
        except Exception as e:
            log(f"background picture failed: {e}")
    PIC = bg_src is not None

    root.attributes("-topmost", bool(store.settings.get("on_top")))
    try:
        root.iconbitmap(default=os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "icon.ico"))
    except Exception:
        pass

    def btn(parent, text, cmd, width=None):
        b = tk.Button(parent, text=text, command=cmd, bg=BTN, fg=T["fg"], activebackground=DIM,
                      activeforeground=T["fg"], relief="solid", bd=1, font=FONT, padx=8, pady=3)
        if width:
            b.configure(width=width)
        return b

    def lbl(parent, text="", fg=None, **kw):
        return tk.Label(parent, text=text, bg=BG, fg=fg or T["fg"], font=FONT, anchor="w", justify="left", **kw)

    # ---------------- the main screen is one canvas: text is written straight onto the background
    import tkinter.font as tkfont
    cv = tk.Canvas(root, bg=BG, highlightthickness=0, bd=0)
    cv.place(x=0, y=0, relwidth=1, relheight=1)
    FNT = tkfont.Font(family="Consolas", size=10)
    LH = FNT.metrics("linespace") + 2
    CW = FNT.measure("0")
    bg_item = cv.create_image(0, 0, anchor="nw")
    bar_btns = []

    def text(x, y, s, fill, tag):
        if PIC:   # dark outline so text stays readable on any picture
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
                cv.create_text(x + dx, y + dy, text=s, anchor="nw", font=FNT, fill="#000000", tags=(tag, "txt"))
        cv.create_text(x, y, text=s, anchor="nw", font=FNT, fill=fill, tags=(tag, tag + "_fg", "txt"))

    def set_text(tag, s, fill=None):
        cv.itemconfigure(tag, text=s)
        if fill:
            cv.itemconfigure(tag + "_fg", fill=fill)

    def toggle():
        if watcher.alive:
            watcher.stop()
        else:
            if not store.accounts:
                messagebox.showinfo("NovaHop Rejoin", "add an account first")
                return
            watcher.start()
        refresh()

    start_btn = btn(cv, "Start", toggle, 8)
    bar_btns.append(start_btn)

    # ---------------- add account
    def add_account():
        add_btn.configure(state="disabled", text="log in in the new window...")
        place_bar()
        out = os.path.join(tempfile.gettempdir(), f"nhr_login_{os.getpid()}_{int(time.time())}.tmp")
        if getattr(sys, "frozen", False):
            cmd = [sys.executable, "--login", out]
        else:
            cmd = [sys.executable, os.path.abspath(__file__), "--login", out]

        def run():
            try:
                subprocess.run(cmd)
                if not os.path.exists(out):
                    log("login window closed without logging in")
                    return
                with open(out, encoding="utf-8") as f:
                    got = json.load(f)
                os.remove(out)
                cookie = core.unprotect(got["cookie"])
                page = got.get("user") or {}
                try:
                    uid, name, display = core.whoami(cookie)
                except core.RobloxError as e:
                    if not page.get("id"):
                        raise
                    uid, name, display = str(page["id"]), page.get("name") or str(page["id"]), page.get("display") or ""
                    log(f"note: roblox's account check said '{e}', used the login page instead")
                store.add(uid, name, display, cookie)
                log(f"added {name} ({uid})")
            except Exception as e:
                log(f"couldn't add account: {e}")
            finally:
                try:
                    os.remove(out)
                except OSError:
                    pass
                root.after(0, lambda: (add_btn.configure(state="normal", text="Add account"), place_bar()))

        threading.Thread(target=run, daemon=True).start()

    add_btn = btn(cv, "Add account", add_account)
    bar_btns.append(add_btn)

    def popup(title, size):
        d = tk.Toplevel(root, bg=BG)
        d.title(title)
        d.geometry(size)
        d.transient(root)
        d.grab_set()
        return d

    # ---------------- game / private server
    def game_dialog():
        d = popup("Game / server", "460x240")
        s = store.settings

        def field(text, value):
            lbl(d, text).pack(anchor="w", padx=10, pady=(8, 0))
            e = tk.Entry(d, bg="#FFFFFF", fg="#000000", insertbackground="#000000", relief="solid", bd=1, font=FONT)
            e.pack(fill="x", padx=10)
            e.insert(0, value)
            return e

        g = field("game link or place id (empty = bee swarm)", "" if s["place"] == core.BEE_SWARM else s["place"])
        p = field("private server link (empty = public)", s.get("ps_raw", ""))
        dl = field("seconds between launches", str(s.get("launch_delay", 12)))

        def save():
            place = core.parse_game(g.get())
            ps = core.parse_ps(p.get())
            try:
                delay = max(5, int(dl.get() or 12))
            except ValueError:
                delay = None
            if place is None or ps is None or delay is None:
                messagebox.showerror("NovaHop Rejoin", "invalid input, nothing changed", parent=d)
                return
            s.update(place=place, ps_raw=p.get().strip(), launch_delay=delay)
            store.save()
            log(f"saved: place {place}, private server: {'yes' if ps[0] else 'no'}")
            d.destroy()

        btn(d, "Save", save).pack(anchor="w", padx=10, pady=10)

    bar_btns.append(btn(cv, "Game / server", game_dialog))

    # ---------------- heartbeat folders
    def folders_dialog():
        d = popup("Folders", "560x320")
        lbl(d, "heartbeat folders (executor workspace\\NovaHop\\hb)").pack(anchor="w", padx=10, pady=(8, 4))
        lst = tk.Frame(d, bg=BG)
        lst.pack(fill="both", expand=True, padx=10)

        def draw():
            for w in lst.winfo_children():
                w.destroy()
            if not store.settings["hb_dirs"]:
                lbl(lst, "none", fg=DIM).pack(anchor="w")
            for path in store.settings["hb_dirs"]:
                row = tk.Frame(lst, bg=BG)
                row.pack(fill="x", pady=1)
                btn(row, "x", lambda p=path: (store.settings["hb_dirs"].remove(p), store.save(), draw())).pack(side="left")
                lbl(row, " " + path).pack(side="left")

        def scan():
            scan_btn.configure(state="disabled", text="scanning...")

            def run():
                watcher.rescan()
                root.after(0, lambda: (draw(), scan_btn.configure(state="normal", text="Scan")))
            threading.Thread(target=run, daemon=True).start()

        def pick():
            path = filedialog.askdirectory(parent=d, title="pick your executor's workspace folder")
            if not path:
                return
            path = os.path.normpath(path)
            hb = path if path.lower().endswith(os.path.join("novahop", "hb")) else os.path.join(path, "NovaHop", "hb")
            os.makedirs(hb, exist_ok=True)
            if hb not in store.settings["hb_dirs"]:
                store.settings["hb_dirs"].append(hb)
                store.save()
                log(f"watching {hb}")
            parent = os.path.dirname(path)
            ax = [os.path.join(parent, n) for n in os.listdir(parent)
                  if os.path.isdir(os.path.join(parent, n)) and n.lower().startswith("autoexec")]
            for a in core.install_heartbeat(ax):
                log(f"heartbeat in {a}")
            draw()

        row = tk.Frame(d, bg=BG)
        row.pack(anchor="w", padx=10, pady=10)
        scan_btn = btn(row, "Scan", scan)
        scan_btn.pack(side="left")
        btn(row, "Pick folder", pick).pack(side="left", padx=4)
        draw()

    bar_btns.append(btn(cv, "Folders", folders_dialog))
    bar_btns.append(btn(cv, "Settings", lambda: settings_dialog()))
    bar_btns.append(btn(cv, "Look", lambda: look_dialog()))
    bar_btns.append(btn(cv, "Discord", lambda: __import__("webbrowser").open(DISCORD)))


    # ---------------- settings
    COLORS = {"white": "#FFFFFF", "green": "#00FF00", "cyan": "#00FFFF", "pink": "#FF77FF",
              "orange": "#FFA500", "purple": "#B388FF", "yellow": "#FFFF66"}

    def recolor(widget, old, new):
        for w in widget.winfo_children():
            try:
                if str(w.cget("fg")).upper() == old.upper():
                    w.configure(fg=new)
                if isinstance(w, tk.Button):
                    w.configure(activeforeground=new)
            except tk.TclError:
                pass
            recolor(w, old, new)

    def settings_dialog():
        d = popup("Settings", "520x1000")
        s = store.settings

        def head(t):
            lbl(d, t, fg=DIM).pack(anchor="w", padx=10, pady=(12, 0))

        def entry(t, v):
            lbl(d, t).pack(anchor="w", padx=10, pady=(4, 0))
            e = tk.Entry(d, bg="#FFFFFF", fg="#000000", insertbackground="#000000", relief="solid", bd=1, font=FONT)
            e.pack(fill="x", padx=10)
            e.insert(0, str(v))
            return e

        def check(t, key):
            v = tk.IntVar(value=1 if s.get(key) else 0)
            tk.Checkbutton(d, text=t, variable=v, bg=BG, fg=T["fg"], activebackground=BG, activeforeground=T["fg"],
                           selectcolor=BG, font=FONT, anchor="w").pack(anchor="w", padx=6)
            return key, v

        head("-- discord webhook --")
        wh = entry("webhook url", s.get("webhook", ""))
        ping = entry("ping user id on problems (optional)", s.get("wh_ping", ""))
        checks = [check("message on every restart", "wh_restart"),
                  check("message when an account is stuck", "wh_stuck"),
                  check("message when a login expires", "wh_login"),
                  check("message on start / stop", "wh_startstop"),
                  check("live status message (accounts, ram, cpu)", "wh_status"),
                  check("add a screenshot to the status", "shots")]
        every = entry("update the status every (seconds, min 30)", s.get("status_every", 60))

        def test():
            url = wh.get().strip()
            if not core.is_webhook(url):
                messagebox.showerror("NovaHop Rejoin", "that doesn't look like a discord webhook url", parent=d)
                return
            try:
                core.send_webhook(url, "NovaHop Rejoin test message")
                messagebox.showinfo("NovaHop Rejoin", "sent, check your discord", parent=d)
            except Exception as e:
                messagebox.showerror("NovaHop Rejoin", f"failed: {e}", parent=d)
        btn(d, "Test webhook", test).pack(anchor="w", padx=10, pady=4)

        head("-- app --")
        checks += [check("start watching when the app opens", "autostart"),
                   check("keep window on top", "on_top"),
                   check("minimize to the tray (next to the clock)", "tray"),
                   check("open roblox windows minimized (they won't steal focus)", "launch_min"),
                   check("check for updates when the app opens", "update_check")]
        lbl(d, "text colour").pack(anchor="w", padx=10, pady=(4, 0))
        cur = next((k for k, v in COLORS.items() if v.upper() == T["fg"].upper()), "white")
        col = tk.StringVar(value=cur)
        om = tk.OptionMenu(d, col, *COLORS)
        om.configure(bg=BTN, fg=T["fg"], activebackground="#333333", activeforeground=T["fg"], relief="solid", bd=1,
                     font=FONT, highlightthickness=0)
        om["menu"].configure(bg=BTN, fg="#FFFFFF", font=FONT)
        om.pack(anchor="w", padx=10)

        head("-- restart timing (seconds) --")
        ew = entry("restart after an error screen stays up", s.get("error_wait", core.ERROR_WAIT))
        st = entry("restart after no heartbeat for", s.get("stale", core.STALE))
        hw = entry("wait for teleport / full server errors (novahop retries these itself)", s.get("hop_wait", core.HOP_WAIT))
        bw = entry("loading time after a launch (raise it if your executor injects slowly)", s.get("boot_wait", core.BOOT_WAIT))

        def save():
            url = wh.get().strip()
            if url and not core.is_webhook(url):
                messagebox.showerror("NovaHop Rejoin", "that doesn't look like a discord webhook url", parent=d)
                return
            try:
                e_w = max(5, int(ew.get())); s_t = max(30, int(st.get())); s["boot_wait"] = max(60, int(bw.get())); s["hop_wait"] = max(10, int(hw.get())); s["status_every"] = max(30, int(every.get()))
            except ValueError:
                messagebox.showerror("NovaHop Rejoin", "timings have to be numbers", parent=d)
                return
            s.update(webhook=url, wh_ping=ping.get().strip(), error_wait=e_w, stale=s_t)
            for key, v in checks:
                s[key] = bool(v.get())
            new = COLORS[col.get()]
            if new.upper() != T["fg"].upper():
                old = T["fg"]
                T["fg"] = new
                s["text_color"] = new
                recolor(root, old, new)
                rows_key[0] = None
            root.attributes("-topmost", bool(s.get("on_top")))
            store.save()
            log("settings saved")
            d.destroy()

        btn(d, "Save", save).pack(anchor="w", padx=10, pady=12)


    # ---------------- look: colours, background picture, see-through window
    def restart_app():
        if getattr(sys, "frozen", False):
            # a fully separate copy: without this it reuses (and then loses) this copy's unpacked files
            env = dict(os.environ, PYINSTALLER_RESET_ENVIRONMENT="1")
            for k in [k for k in env if k.startswith("_PYI") or k.startswith("_MEI")]:
                env.pop(k)
            subprocess.Popen([sys.executable], env=env, close_fds=True)
        else:
            subprocess.Popen([sys.executable, os.path.abspath(__file__)])
        root.destroy()
        os._exit(0)

    def look_dialog():
        from tkinter import colorchooser
        d = popup("Look", "500x540")
        s = store.settings
        new = {k: s.get(k) for k in ("bg_color", "btn_color", "bg_image", "alpha", "show_log", "bg_mode")}
        new["bg_mode"] = new["bg_mode"] or "fill"

        def color_row(text, key):
            row = tk.Frame(d, bg=BG)
            row.pack(fill="x", padx=10, pady=4)
            sw = tk.Label(row, width=4, bg=new[key], relief="solid", bd=1)
            sw.pack(side="left")
            lbl(row, " " + text).pack(side="left")

            def pick():
                c = colorchooser.askcolor(color=new[key], parent=d, title=text)[1]
                if c:
                    new[key] = c
                    sw.configure(bg=c)
            btn(row, "pick", pick).pack(side="right")

        lbl(d, "-- colours --", fg=DIM).pack(anchor="w", padx=10, pady=(10, 0))
        color_row("background colour", "bg_color")
        color_row("button colour", "btn_color")
        lbl(d, "(text colour is in Settings)", fg=DIM).pack(anchor="w", padx=10)

        lbl(d, "-- background picture --", fg=DIM).pack(anchor="w", padx=10, pady=(12, 0))
        pic = lbl(d, os.path.basename(new["bg_image"]) if new["bg_image"] else "none")
        pic.pack(anchor="w", padx=10)
        prow = tk.Frame(d, bg=BG)
        prow.pack(anchor="w", padx=10, pady=4)

        def choose():
            f = filedialog.askopenfilename(parent=d, title="pick a background picture",
                                           filetypes=[("pictures", "*.png *.jpg *.jpeg *.gif *.bmp *.webp")])
            if not f:
                return
            try:
                from PIL import Image
                im = Image.open(f).convert("RGB")
                im.thumbnail((2560, 1440))
                dest = os.path.join(core.DATA_DIR, "background.png")
                os.makedirs(core.DATA_DIR, exist_ok=True)
                im.save(dest)                 # keep our own copy, so moving the original doesn't break it
                new["bg_image"] = dest
                pic.configure(text=os.path.basename(f))
            except Exception as e:
                messagebox.showerror("NovaHop Rejoin", f"couldn't open that picture: {e}", parent=d)

        def clear():
            new["bg_image"] = ""
            pic.configure(text="none")
        btn(prow, "Choose picture", choose).pack(side="left")
        btn(prow, "Remove", clear).pack(side="left", padx=4)

        mrow = tk.Frame(d, bg=BG)
        mrow.pack(anchor="w", padx=10, pady=(2, 0))
        lbl(mrow, "picture size: ").pack(side="left")
        mode_var = tk.StringVar(value=new["bg_mode"])
        mm = tk.OptionMenu(mrow, mode_var, "fill", "fit", "stretch", "center", "tile")
        mm.configure(bg=BTN, fg=T["fg"], activebackground=DIM, activeforeground=T["fg"], relief="solid", bd=1,
                     font=FONT, highlightthickness=0)
        mm["menu"].configure(bg=BTN, fg="#FFFFFF", font=FONT)
        mm.pack(side="left")
        lbl(d, "fill = covers the window   fit = whole picture   tile = repeat a small one", fg=DIM).pack(anchor="w", padx=10)

        lbl(d, "-- window --", fg=DIM).pack(anchor="w", padx=10, pady=(12, 0))
        lbl(d, "see-through (100 = solid)").pack(anchor="w", padx=10)
        op = tk.Scale(d, from_=40, to=100, orient="horizontal", bg=BG, fg=T["fg"], troughcolor=BTN,
                      highlightthickness=0, font=FONT, length=260)
        op.set(int(float(new["alpha"] or 1.0) * 100))
        op.configure(command=lambda v: root.attributes("-alpha", int(v) / 100))
        op.pack(anchor="w", padx=10)
        sl = tk.IntVar(value=1 if new["show_log"] else 0)
        tk.Checkbutton(d, text="show the log", variable=sl, bg=BG, fg=T["fg"], activebackground=BG,
                       activeforeground=T["fg"], selectcolor=BG, font=FONT).pack(anchor="w", padx=6)

        def save():
            new["alpha"] = int(op.get()) / 100
            new["show_log"] = bool(sl.get())
            new["bg_mode"] = mode_var.get()
            changed = any(new[k] != s.get(k) for k in ("bg_color", "btn_color", "bg_image", "show_log", "bg_mode"))
            s.update(new)
            store.save()
            d.destroy()
            if changed and messagebox.askyesno("NovaHop Rejoin", "restart the app now to apply the new look?\n"
                                                                 "(your roblox windows keep running)"):
                restart_app()

        def reset():
            new.update(bg_color="#000000", btn_color="#1A1A1A", bg_image="", alpha=1.0, show_log=True)
            mode_var.set("fill")
            op.set(100)
            sl.set(1)
            save()

        row = tk.Frame(d, bg=BG)
        row.pack(anchor="w", padx=10, pady=12)
        btn(row, "Save", save).pack(side="left")
        btn(row, "Reset to default", reset).pack(side="left", padx=4)

    # ---------------- accounts
    def close_all():
        if messagebox.askyesno("NovaHop Rejoin", "close every roblox window this app launched?"):
            watcher.close_all()
    rows = {}
    rows_key = [None]
    row_widgets = []
    log_view = {"y": 0, "lines": []}
    shown = [0]

    def remove(a):
        if messagebox.askyesno("NovaHop Rejoin", f"remove {a['name']}? its saved login gets deleted"):
            store.remove(a["uid"])

    def rename(a):
        from tkinter import simpledialog
        n = simpledialog.askstring("Rename", f"nickname for {a['name']} (empty = username)",
                                   initialvalue=a.get("nick", ""), parent=root)
        if n is not None:
            a["nick"] = n.strip()
            store.save()
            rows_key[0] = None

    def server_dialog(a):
        d = popup(f"Server for {a.get('nick') or a['name']}", "460x260")
        own = tk.IntVar(value=1 if a.get("own_server") else 0)
        tk.Checkbutton(d, text="use its own game / private server", variable=own, bg=BG, fg=T["fg"],
                       activebackground=BG, activeforeground=T["fg"], selectcolor=BG, font=FONT).pack(anchor="w", padx=6, pady=(10, 0))
        lbl(d, "(off = uses the main Game / server setting)", fg=DIM).pack(anchor="w", padx=10)

        def field(text, value):
            lbl(d, text).pack(anchor="w", padx=10, pady=(8, 0))
            e = tk.Entry(d, bg="#FFFFFF", fg="#000000", insertbackground="#000000", relief="solid", bd=1, font=FONT)
            e.pack(fill="x", padx=10)
            e.insert(0, value)
            return e
        place = a.get("place", "")
        g = field("game link or place id (empty = bee swarm)", "" if place in ("", core.BEE_SWARM) else place)
        p = field("private server link (empty = public)", a.get("ps_raw", ""))

        def save():
            pl = core.parse_game(g.get())
            ps = core.parse_ps(p.get())
            if pl is None or ps is None:
                messagebox.showerror("NovaHop Rejoin", "invalid input, nothing changed", parent=d)
                return
            a.update(own_server=bool(own.get()), place=pl, ps_raw=p.get().strip())
            store.save()
            log(f"{a['name']}: " + (f"own server, place {pl}, private: {'yes' if ps[0] else 'no'}" if own.get() else "uses the main server setting"))
            d.destroy()
        btn(d, "Save", save).pack(anchor="w", padx=10, pady=10)

    def set_enabled(a, var):
        a["enabled"] = bool(var.get())
        store.save()

    def place_bar():
        cv.delete("barwin")
        cv.update_idletasks()
        x = 12
        for b in bar_btns:
            cv.create_window(x, layout_y["bar"], window=b, anchor="nw", tags="barwin")
            x += b.winfo_reqwidth() + 6

    layout_y = {"bar": 12 + LH * 2 + 6}

    def layout():
        """(re)draw everything except the background"""
        cv.delete("txt")
        cv.delete("rowwin")
        for w in row_widgets:
            w.destroy()
        row_widgets.clear()
        rows.clear()
        x0 = 12
        text(x0, 10, "status: stopped", T["fg"], "status")
        text(x0, 10 + LH, "", DIM, "info")
        place_bar()
        bh = start_btn.winfo_reqheight()
        y = layout_y["bar"] + bh + 14

        def rbtn(t, cmd, x, yy):
            b = btn(cv, t, cmd)
            row_widgets.append(b)
            cv.create_window(x, yy, window=b, anchor="nw", tags="rowwin")
            return x + b.winfo_reqwidth() + 4

        text(x0, y + 4, "accounts:", T["fg"], "acc_h")
        x = x0 + FNT.measure("accounts: ") + 6
        x = rbtn("Launch all", watcher.launch_all, x, y)
        rbtn("Close all", close_all, x, y)
        y += bh + 8
        if not store.accounts:
            text(x0, y, "no accounts", DIM, "noacc")
            y += LH + 6
        for a in store.accounts:
            var = tk.IntVar(value=1 if a.get("enabled", True) else 0)
            cb = tk.Checkbutton(cv, variable=var, bg=BTN, activebackground=BTN, selectcolor=BG, fg=T["fg"],
                                bd=0, highlightthickness=0, command=lambda a=a, v=var: set_enabled(a, v))
            cb.var = var
            row_widgets.append(cb)
            cv.create_window(x0, y + 2, window=cb, anchor="nw", tags="rowwin")
            tag = "nm_" + a["uid"]
            text(x0 + 28, y + 4, a.get("nick") or a["name"], T["fg"], tag)
            cv.tag_bind(tag, "<Double-Button-1>", lambda e, a=a: rename(a))
            text(x0 + 28 + CW * 18, y + 4, "", DIM, "st_" + a["uid"])
            rows[a["uid"]] = "st_" + a["uid"]
            x = x0 + 28 + CW * 50
            x = rbtn("Launch", lambda u=a["uid"]: watcher.launch_now(u), x, y)
            x = rbtn("Server", lambda a=a: server_dialog(a), x, y)
            x = rbtn("Close", lambda u=a["uid"]: watcher.close(u), x, y)
            rbtn("x", lambda a=a: remove(a), x, y)
            y += bh + 4
        if store.settings.get("show_log", True):
            y += 10
            text(x0, y + 4, "log:", T["fg"], "log_h")
            rbtn("clear", clear_log, x0 + FNT.measure("log: ") + 6, y)
            y += bh + 6
            log_view["y"] = y
            text(x0, y, "", DIM, "logtxt")
            draw_log()

    def draw_log():
        if not store.settings.get("show_log", True) or not log_view["y"]:
            return
        h = max(cv.winfo_height(), root.winfo_height())
        n = max(1, (h - log_view["y"] - 10) // LH)
        set_text("logtxt", "\n".join(log_view["lines"][-n:]))

    def clear_log():
        log_view["lines"].clear()
        draw_log()

    def color(s):
        if s == "OK":
            return "#00FF00"
        if s in ("off", "not watching", "not started"):
            return DIM
        if any(s.startswith(x) for x in ("loading", "starting", "launching", "waiting", "window closed")):
            return "#FFFF00"
        return "#FF4040"

    def refresh():
        on = watcher.alive
        key = ",".join(a["uid"] for a in store.accounts) + "|" + T["fg"]
        if key != rows_key[0]:
            rows_key[0] = key
            layout()
        set_text("status", "status: running" if on else "status: stopped")
        if start_btn.cget("text") != ("Stop" if on else "Start"):
            start_btn.configure(text="Stop" if on else "Start")
        s = store.settings
        game = "bee swarm" if s["place"] == core.BEE_SWARM else f"place {s['place']}"
        set_text("info", f"{game} | {'private server' if s.get('ps_raw') else 'public'} | {len(s['hb_dirs'])} hb folder(s)")
        for a in store.accounts:
            tag = rows.get(a["uid"])
            if tag is None:
                continue
            t = watcher.status.get(a["uid"], "not started")
            if not a.get("enabled", True):
                t = "off"
            elif not on and not t.startswith("login expired"):
                t = "not watching"
            r = watcher.restarts.get(a["uid"], 0)
            set_text(tag, f"{t} ({r} restarts)" if r else t, color(t))
        with lock:
            new = [t for n, t in lines if n > shown[0]]
            shown[0] = count[0]
        if new:
            log_view["lines"].extend(new)
            del log_view["lines"][:-300]
            draw_log()

    bg_state = {"size": None, "job": None}

    def fit_bg():
        if not PIC:
            return
        try:
            w, h = root.winfo_width(), root.winfo_height()
            if w < 50 or h < 50:
                root.after(200, fit_bg)
                return
            if bg_state["size"] == (w, h):
                return
            bg_state["size"] = (w, h)
            mode = store.settings.get("bg_mode", "fill")
            if mode == "stretch":
                out = bg_src.resize((w, h))
            elif mode in ("fit", "center"):
                out = Image.new("RGB", (w, h), BG)
                r = min(w / bg_src.width, h / bg_src.height) if mode == "fit" else min(1.0, w / bg_src.width, h / bg_src.height)
                im = bg_src.resize((max(1, int(bg_src.width * r)), max(1, int(bg_src.height * r))))
                out.paste(im, ((w - im.width) // 2, (h - im.height) // 2))
            elif mode == "tile":
                out = Image.new("RGB", (w, h), BG)
                for yy in range(0, h, bg_src.height):
                    for xx in range(0, w, bg_src.width):
                        out.paste(bg_src, (xx, yy))
            else:                                                     # fill: cover the window, crop the rest
                r = max(w / bg_src.width, h / bg_src.height)
                im = bg_src.resize((int(bg_src.width * r) + 1, int(bg_src.height * r) + 1))
                l, t = (im.width - w) // 2, (im.height - h) // 2
                out = im.crop((l, t, l + w, t + h))
            cache = os.path.join(core.DATA_DIR, "background_fit.png")
            out.save(cache)
            cv.img = tk.PhotoImage(file=cache)                       # Tk reads PNG itself
            cv.itemconfigure(bg_item, image=cv.img)
            cv.tag_lower(bg_item)
        except Exception as e:
            log(f"background picture failed: {e}")

    def on_resize(e):
        if e.widget is root:
            if bg_state["job"]:
                root.after_cancel(bg_state["job"])
            bg_state["job"] = root.after(150, lambda: (fit_bg(), draw_log()))
    root.bind("<Configure>", on_resize, add="+")
    root.after(300, fit_bg)

    def tick():
        refresh()
        root.after(1000, tick)

    # ---------------- tray
    tray = {"icon": None}
    icon_file = os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "icon.ico")

    def quit_app():
        if tray["icon"]:
            try:
                tray["icon"].stop()
            except Exception:
                pass
        root.destroy()
        os._exit(0)

    def restore():
        if tray["icon"]:
            try:
                tray["icon"].stop()
            except Exception:
                pass
            tray["icon"] = None
        root.deiconify()
        root.lift()
        root.focus_force()

    def to_tray():
        try:
            import pystray
            from PIL import Image
        except Exception:
            return False
        try:
            img = Image.open(icon_file)
        except Exception:
            img = Image.new("RGB", (64, 64), (0, 0, 0))
        menu = pystray.Menu(
            pystray.MenuItem("Show", lambda i, it: root.after(0, restore), default=True),
            pystray.MenuItem(lambda it: "Stop watching" if watcher.alive else "Start watching",
                             lambda i, it: root.after(0, toggle)),
            pystray.MenuItem("Quit", lambda i, it: root.after(0, quit_app)))
        tray["icon"] = pystray.Icon("NovaHopRejoin", img, "NovaHop Rejoin", menu)
        tray["icon"].run_detached()
        root.withdraw()
        return True

    def on_unmap(e):
        if e.widget is root and root.state() == "iconic" and store.settings.get("tray", True) and not tray["icon"]:
            root.after(10, to_tray)
    root.bind("<Unmap>", on_unmap, add="+")

    # ---------------- update check
    VERSION_FILE = os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "version.txt")
    try:
        with open(VERSION_FILE, encoding="utf-8-sig") as f:
            my_version = int(f.read().strip())
    except Exception:
        my_version = None
    root.title(f"NovaHop Rejoin v1.0.{my_version}" if my_version else "NovaHop Rejoin")

    def check_updates():
        try:
            newest = core.latest_version()
        except Exception:
            return
        if my_version and newest and newest > my_version:
            def ask():
                log(f"update available: v1.0.{newest} (you have v1.0.{my_version})")
                if messagebox.askyesno("NovaHop Rejoin", f"a new version is out (v1.0.{newest}).\nopen the download page?"):
                    __import__("webbrowser").open(f"https://github.com/{core.REPO}/releases/latest")
            root.after(0, ask)
    if store.settings.get("update_check", True):
        threading.Thread(target=check_updates, daemon=True).start()

    log("ready. close all roblox windows before pressing start")
    if store.settings.get("autostart") and store.accounts:
        root.after(1500, toggle)
    tick()
    root.mainloop()


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--login":
        login_mode(sys.argv[2])
    else:
        app_mode()
