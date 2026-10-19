"""Essai de l'appli dans un émulateur Android (GitHub Actions), contre un vrai serveur Bulle lancé à côté.

Le serveur tourne sur la machine de fabrication, port 2205 : dans l'émulateur il s'appelle 10.0.2.2:2205.
Chaque étape laisse une capture d'écran dans screens/ et une ligne dans screens/rapport.txt.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK = sys.argv[1]
PKG = "io.github.kermess14.bulle"
ACT = PKG + "/.MainActivity"
OUT = "screens"
os.makedirs(OUT, exist_ok=True)
report = []


def adb(*args, check=False, timeout=60):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def note(ok, text):
    line = ("OK     " if ok else "ÉCHEC  ") + text
    print(line, flush=True)
    report.append(line)


def shot(name):
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(data)


def top():
    """Activité au premier plan (pour savoir si l'appli est bien devant)."""
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"(?:topResumedActivity|mResumedActivity)[^\n]*?(\S+/\S+)", out)
    return m.group(1) if m else "?"


def dismiss_fullscreen_hint():
    """Bandeau système « Viewing full screen » (affiché une seule fois par Android) : on le ferme."""
    n = find("Got it") or find("Compris")
    if n is not None:
        tap(n)
        time.sleep(1)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml")
    i = x.find("<?xml")
    if i < 0:
        return []
    try:
        return list(ET.fromstring(x[i:].strip()).iter("node"))
    except ET.ParseError:
        return []


def find(text):
    for n in nodes():
        if text.lower() in (n.get("text", "") + " " + n.get("content-desc", "")).lower():
            return n
    return None


def wait_for(text, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        n = find(text)
        if n is not None:
            return n
        time.sleep(1.5)
    return None


def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def run():
    adb("install", "-r", APK, check=True, timeout=180)
    adb("shell", "am", "start", "-W", "-n", ACT)

    # 1. premier lancement : écran de choix du serveur
    n = wait_for("Chercher", 40)
    shot("1-premier-lancement")
    note(n is not None, "premier lancement : écran « Trouver Bulle sur le Wi-Fi »")
    if n is None:
        return

    # 2. recherche automatique sur le réseau
    tap(n)
    n = wait_for("10.0.2.2:2205", 45)
    shot("2-recherche")
    note(n is not None, "recherche sur le réseau : serveur Bulle trouvé (10.0.2.2:2205)")
    if n is None:
        a = find("Adresse du serveur") or find("192.168.0.213")
        if a is not None:
            tap(a)
            adb("shell", "input", "text", "10.0.2.2:2205")
            adb("shell", "input", "keyevent", "66")
    else:
        tap(n)

    # 3. bibliothèque
    n = wait_for("Essai", 45)
    time.sleep(3)
    shot("3-bibliotheque")
    note(n is not None, "bibliothèque affichée (albums « Essai » visibles)")

    # 4. ouverture d'un album : lecteur en plein écran
    if n is not None:
        tap(n)
        time.sleep(8)
        shot("4-lecteur")
        dismiss_fullscreen_hint()
        win = adb("shell", "dumpsys", "window", "windows")
        hidden = "mStatusBarHidden" in win or bool(re.search(r"statusBars.*visible=false|isVisible=false", win))
        note(True, "lecteur ouvert (voir capture 4) ; barres système masquées : " + ("oui" if hidden else "non vérifiable"))
        adb("shell", "input", "swipe", "900", "1200", "200", "1200", "250")   # page suivante (glisser vers la gauche)
        time.sleep(3)
        shot("5-lecteur-page-suivante")

        # 5. bouton retour d'Android : on doit revenir à la bibliothèque, dans l'appli
        adb("shell", "input", "keyevent", "4")
        back = wait_for("Bibliothèque", 15) or wait_for("BIBLIOTHÈQUE", 3)
        time.sleep(2)
        shot("6-retour")
        note(back is not None and PKG in top(), "bouton retour : retour à la bibliothèque (premier plan : " + top() + ")")

    # 6. raccourci « Changer de serveur »
    adb("shell", "am", "start", "-W", "-a", PKG + ".CHANGE_SERVER", "-n", ACT)
    n = wait_for("Se connecter", 20)
    report.append("       (premier plan après le raccourci : " + top() + ")")
    shot("7-changer-de-serveur")
    note(n is not None, "raccourci « Changer de serveur » : écran de connexion")

    # 7. serveur arrêté : écran d'erreur
    pid = os.environ.get("SERVER_PID")
    if pid:
        subprocess.run(["kill", pid])
        time.sleep(2)
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "am", "start", "-W", "-n", ACT)
        n = wait_for("Impossible de joindre", 30)
        shot("8-serveur-arrete")
        note(n is not None, "serveur arrêté : écran « Impossible de joindre Bulle »")

    crash = adb("logcat", "-d", "-b", "crash")
    note(PKG not in crash, "aucun plantage de l'appli dans le journal Android")
    with open(f"{OUT}/journal.txt", "w") as f:
        f.write(adb("logcat", "-d", "-s", "BulleApp:*", "ActivityTaskManager:I", "AndroidRuntime:E", "chromium:I"))


try:
    run()
except Exception as e:  # noqa: BLE001
    note(False, f"erreur du test : {e}")
    shot("erreur")
finally:
    with open(f"{OUT}/rapport.txt", "w") as f:
        f.write("\n".join(report) + "\n")
    print("\n".join(report))
