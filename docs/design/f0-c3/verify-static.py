"""Read-only static verification for the F0-C3 design delivery."""

import hashlib
import json
import re
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DESIGN = ROOT / "docs/design"
PACKAGE = DESIGN / "f0-c3"


def luminance(value):
    rgb = [int(value[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    rgb = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]
    return sum(v * w for v, w in zip(rgb, [.2126, .7152, .0722]))


def contrast(a, b):
    low, high = sorted([luminance(a), luminance(b)])
    return (high + .05) / (low + .05)


def main():
    tokens = json.loads((PACKAGE / "design-tokens.json").read_text(encoding="utf-8"))
    assert len(tokens["colorScheme"]["light"]) == 48
    assert tokens["colorScheme"]["light"].keys() == tokens["colorScheme"]["dark"].keys()
    rows = []
    pairs = [("onPrimary", "primary"), ("onPrimaryContainer", "primaryContainer"),
             ("onSecondary", "secondary"), ("onSecondaryContainer", "secondaryContainer"),
             ("onTertiary", "tertiary"), ("onTertiaryContainer", "tertiaryContainer"),
             ("onError", "error"), ("onErrorContainer", "errorContainer"),
             ("onSurface", "surface"), ("onSurfaceVariant", "surfaceVariant"),
             ("inverseOnSurface", "inverseSurface"),
             ("onPrimaryFixedVariant", "primaryFixedDim"),
             ("onSecondaryFixedVariant", "secondaryFixedDim"),
             ("onTertiaryFixedVariant", "tertiaryFixedDim")]
    for theme, colors in tokens["colorScheme"].items():
        for foreground, background in pairs:
            value = contrast(colors[foreground], colors[background])
            rows.append(dict(theme=theme, foreground=foreground, background=background, ratio=round(value, 3), target=4.5))
        for background in ["surface", "surfaceContainerLowest", "surfaceContainerHighest"]:
            value = contrast(tokens["record"][theme]["muted"], colors[background])
            rows.append(dict(theme=theme, foreground="record.muted", background=background, ratio=round(value, 3), target=4.5))
        value = contrast(colors["outline"], colors["surface"])
        rows.append(dict(theme=theme, foreground="outline", background="surface", ratio=round(value, 3), target=3))
    assert all(row["ratio"] >= row["target"] for row in rows)
    files = [DESIGN / "record2-f0-c3-review.md", DESIGN / "record2-f0-c3-contract-delta.md",
             DESIGN / "record2-uiux-upgrade-proposal-20261024.md", ROOT / "record2/PRODUCT.md"]
    count = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        assert "\ufffd" not in text, path
        assert sum(line.startswith("```") for line in text.splitlines()) % 2 == 0, path
        for target in re.findall(r"\]\(([^\s)]+)", text):
            if target.startswith(("http:", "https:", "mailto:", "#")):
                continue
            target = target.split("#")[0].strip("<>")
            if target:
                assert (path.parent / target).exists(), (path, target)
                count += 1
    image_manifest = json.loads((PACKAGE / "image-generation.json").read_text(encoding="utf-8"))
    edits = json.loads((PACKAGE / "image-generation-edits.json").read_text(encoding="utf-8"))
    images = []
    for entry in image_manifest["images"] + edits["images"]:
        path = PACKAGE / "images" / entry["file"]
        data = path.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n"
        width, height = struct.unpack(">II", data[16:24])
        images.append(dict(file=entry["file"], width=width, height=height, sha256=hashlib.sha256(data).hexdigest()))
    svg_files = list((PACKAGE / "icons").glob("*.svg"))
    assert len(svg_files) == 19
    assert (PACKAGE / "icons/LICENSE").exists()
    preview = (DESIGN / "record2-f0-c3-preview.html").read_text(encoding="utf-8")
    assert re.findall(r"fetch\('([^']+)'\)", preview) == ["f0-c3/design-tokens.json"]
    assert not re.search(r"\b(?:localStorage|sessionStorage|XMLHttpRequest|WebSocket)\b", preview)
    hashes = {str(p.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in [DESIGN / "record2-f0-c3-preview.html", PACKAGE / "design-tokens.json", PACKAGE / "verify-preview.js"]}
    print(json.dumps(dict(version="F0-C3", status="PASS", colorRolesPerTheme=48,
                          contrastPairs=rows, localLinks=count, icons=19, images=images,
                          sourceHashes=hashes), ensure_ascii=True))


if __name__ == "__main__":
    main()
