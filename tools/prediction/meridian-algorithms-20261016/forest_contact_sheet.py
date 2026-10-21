"""Make a nearest-neighbor magnified comparison from saved alpha images.

This is a pixel-inspection artifact, not a new rendering or a game screenshot.
Run after collect_results.py. Requires Pillow; changes no experiment results.
"""
import csv
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def main():
    folder = Path(__file__).resolve().parent
    raw = folder / "results/neoforge"
    with (raw / "engine-forest-quality.csv").open(newline="") as stream:
        quality = list(csv.DictReader(stream))
    font_path = Path("C:/Windows/Fonts/segoeui.ttf")
    font = ImageFont.truetype(str(font_path), 17)
    title_font = ImageFont.truetype(str(font_path), 20)
    canvas = Image.new("RGB", (920, 950), "#101923")
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 14), "Saved forest alpha images | 1,024 blocks | yaw 0", fill="white", font=title_font)
    draw.text((24, 45), "Same crop, nearest-neighbor 6x magnification; 2-block binary canopy only", fill="#bbcbdc", font=font)
    draw.text((24, 77), "Source geometry", fill="white", font=title_font)
    draw.text((472, 77), "Candidate: fills some leaf gaps", fill="#f3c66b", font=title_font)
    for row_index, mesh in enumerate((1511, 739, 1476)):
        images = [Image.open(raw / f"forest-{mesh}-1024-0-{mode}.png").convert("RGBA") for mode in (0, 1)]
        boxes = [image.getbbox() for image in images]
        left = min(box[0] for box in boxes) - 4
        top = min(box[1] for box in boxes) - 4
        right = max(box[2] for box in boxes) + 4
        bottom = max(box[3] for box in boxes) + 4
        y = 118 + row_index * 270
        metric = next(r for r in quality if r["mesh"] == str(mesh) and r["distance"] == "1024" and r["yaw"] == "0" and r["mode"] == "1")
        for column, image in enumerate(images):
            x = 24 + column * 448
            panel = Image.new("RGB", (424, 230), "#1d2a36")
            crop = image.crop((left, top, right, bottom))
            crop = crop.resize((crop.width * 6, crop.height * 6), Image.Resampling.NEAREST)
            assert crop.width <= panel.width and crop.height <= panel.height
            panel.paste(crop, ((panel.width - crop.width) // 2, (panel.height - crop.height) // 2), crop)
            canvas.paste(panel, (x, y))
            label = f"Mesh {mesh}" if column == 0 else f"IoU {float(metric['iou']):.3f} | added pixels {float(metric['added_fraction']):.1%}"
            draw.text((x, y + 234), label, fill="#cbd8e5", font=font)
    draw.text((24, 923), "Offline component test. These images are not real-game FPS measurements.", fill="#95a7bb", font=font)
    path = folder / "forest-canopy-comparison.png"
    canvas.save(path)
    print(path)


if __name__ == "__main__":
    main()
