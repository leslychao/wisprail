"""Compare equal client areas; metrics locate differences, not certify acceptance.

Run from the repository root after DesignScenariosTest and capture-reference.cjs.
Requires Pillow. All outputs stay under desktop/target/visual.
"""

import html
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance, ImageStat


def main():
    directory = Path("desktop/target/visual")
    scenarios = json.loads((directory / "scenarios.json").read_text(encoding="utf8"))
    output = directory / "comparison"
    output.mkdir(parents=True, exist_ok=True)
    metrics = []
    cards = []
    for scenario in scenarios:
        name = scenario["id"]
        reference = Image.open(directory / "reference" / f"{name}.png").convert("RGB")
        actual = Image.open(directory / "javafx" / f"{name}.png").convert("RGB")
        if reference.size != actual.size or actual.size != (1070, 700):
            raise ValueError(f"Unequal client geometry: {name}: {reference.size}, {actual.size}")
        difference = ImageChops.difference(reference, actual)
        Image.blend(reference, actual, 0.5).save(output / f"{name}-overlay.png")
        ImageEnhance.Contrast(difference).enhance(3).save(output / f"{name}-diff.png")
        channel_max = ImageChops.lighter(*difference.split()[:2])
        channel_max = ImageChops.lighter(channel_max, difference.split()[2])
        histogram = channel_max.histogram()
        changed = sum(histogram[25:]) / (actual.width * actual.height)
        metrics.append(dict(id=name, meanAbsoluteError=round(sum(ImageStat.Stat(difference).mean) / 3, 3),
                            pixelsOver24Percent=round(changed * 100, 2), result="REVIEW"))
        title = html.escape(scenario["title"])
        cards.append(f'<article id="{name}"><h2>{name}: {title}</h2>'
                     f'<div><figure><figcaption>HTML</figcaption><img src="../reference/{name}.png"></figure>'
                     f'<figure><figcaption>JavaFX</figcaption><img src="../javafx/{name}.png"></figure></div>'
                     f'<a href="{name}-overlay.png">Наложение 50/50</a> · '
                     f'<a href="{name}-diff.png">Различия ×3</a> · '
                     f'{changed:.1%} пикселей с отличием канала >24</article>')
    (output / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf8")
    (output / "index.html").write_text(
        '<!doctype html><html lang="ru"><meta charset="utf-8"><title>Wisprail: 41 сравнение</title>'
        '<style>body{font:14px "Segoe UI",sans-serif;background:#f4f6fa;margin:24px}'
        'article{margin-bottom:36px}article div{display:flex;gap:12px}figure{margin:0;width:50%}'
        'img{width:100%;border:1px solid #ccd2dc}a{color:#365ad8}</style>'
        '<h1>1070 × 700, масштаб 100%</h1><p>HTML и настоящий рендер JavaFX. '
        'Диалоги JavaFX наложены по фактическим координатам их окон. '
        'Метрики включают текст и растеризацию; порога автоматической приёмки нет. '
        'Сетевые состояния заданы только тестовыми фикстурами. '
        'Результаты и ограничения каждого сценария: docs/acceptance.md.</p>'
        + "\n".join(cards) + '</html>', encoding="utf8")
    print(f"Compared {len(metrics)} scenarios; {output / 'index.html'}")


if __name__ == "__main__":
    main()
