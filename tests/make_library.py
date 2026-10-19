"""Petite bibliothèque de BD d'essai (CBZ dessinés) pour tester l'appli contre un vrai serveur Bulle."""
import io, os, random, sys, zipfile
from PIL import Image, ImageDraw

root = sys.argv[1] if len(sys.argv) > 1 else "lib"
os.makedirs(root, exist_ok=True)
W, H = 1200, 1700


def planche(seed, title=None):
    random.seed(seed)
    img = Image.new("RGB", (W, H), (250, 247, 240))
    d = ImageDraw.Draw(img)
    y, gut, m = 90, 30, 70
    for r in range(3):
        rh = (H - 2 * m - 2 * gut) // 3
        cols = random.choice([1, 2, 2, 3])
        cw = (W - 2 * m - gut * (cols - 1)) // cols
        for c in range(cols):
            x = m + c * (cw + gut)
            d.rectangle([x, y, x + cw, y + rh], fill=tuple(random.randint(120, 230) for _ in range(3)), outline=(15, 15, 15), width=8)
            for _ in range(8):
                cx, cy, rr = random.randint(x + 40, x + cw - 40), random.randint(y + 40, y + rh - 40), random.randint(12, 50)
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=tuple(random.randint(0, 255) for _ in range(3)))
        y += rh + gut
    if title:
        d.rectangle([0, 0, W, H], fill=(40, 90, 160))
        d.text((W // 2 - 120, H // 2 - 40), title, fill=(255, 220, 60), font_size=80)
    return img


for n, name in enumerate(["Essai - T01", "Essai - T02", "Autre serie - T01"], start=1):
    with zipfile.ZipFile(os.path.join(root, name + ".cbz"), "w") as z:
        for p in range(8):
            b = io.BytesIO()
            planche(n * 100 + p, title=name if p == 0 else None).save(b, "JPEG", quality=85)
            z.writestr(f"{p:03d}.jpg", b.getvalue())
print("bibliothèque d'essai prête :", sorted(os.listdir(root)))
