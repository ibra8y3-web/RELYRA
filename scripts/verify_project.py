#!/usr/bin/env python3
"""Validate RELYRA's bilingual resources and local documentation assets.

Uses only Python's standard library so it runs on a clean GitHub Actions runner.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def string_resources(path: Path) -> dict[str, str]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        fail(f"{path.relative_to(ROOT)}: invalid XML: {exc}")
        return {}
    return {
        node.attrib["name"]: "".join(node.itertext())
        for node in root.findall("string")
        if "name" in node.attrib
    }


def placeholder_signature(value: str) -> tuple[str, ...]:
    # Android positional format strings, e.g. %1$s or %2$d.
    found = re.findall(r"%(?:(\d+)\$)?([sdif])", value)
    if found and all(index for index, _kind in found):
        return tuple(sorted(f"{index}:{kind}" for index, kind in found))
    if found:
        return tuple(sorted(f"seq:{kind}" for _index, kind in found))
    return ()


def check_translations() -> None:
    english_path = ROOT / "app/src/main/res/values/strings.xml"
    arabic_path = ROOT / "app/src/main/res/values-ar/strings.xml"
    english = string_resources(english_path)
    arabic = string_resources(arabic_path)

    missing_ar = sorted(set(english) - set(arabic))
    missing_en = sorted(set(arabic) - set(english))
    if missing_ar:
        fail("Arabic strings missing: " + ", ".join(missing_ar))
    if missing_en:
        fail("English strings missing: " + ", ".join(missing_en))

    for key in sorted(set(english) & set(arabic)):
        en_sig = placeholder_signature(english[key])
        ar_sig = placeholder_signature(arabic[key])
        if en_sig != ar_sig:
            fail(f"placeholder mismatch for string '{key}': en={en_sig}, ar={ar_sig}")


def local_target_exists(source: Path, raw_target: str) -> bool:
    target = raw_target.strip().strip("<>")
    if not target or target.startswith("#"):
        return True
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target):
        return True  # http(s), mailto, data, and other explicit URI schemes
    target = target.split()[0].split("#", 1)[0].split("?", 1)[0]
    if not target:
        return True
    decoded = unquote(target)
    candidate = (source.parent / decoded).resolve()
    try:
        candidate.relative_to(ROOT.resolve())
    except ValueError:
        return False
    return candidate.exists()


def check_markdown_links() -> None:
    markdown_files = [ROOT / "README.md", ROOT / "README_AR.md"]
    markdown_files.extend(sorted((ROOT / "docs").glob("*.md")))
    markdown_link = re.compile(r"!?(?:\[[^\]]*\])\(([^)]+)\)")
    html_src = re.compile(r"""\bsrc\s*=\s*["']([^"']+)["']""", re.IGNORECASE)

    for path in markdown_files:
        if not path.is_file():
            fail(f"missing documentation file: {path.relative_to(ROOT)}")
            continue
        content = path.read_text(encoding="utf-8")
        for match in markdown_link.finditer(content):
            target = match.group(1).strip()
            if not local_target_exists(path, target):
                fail(f"{path.relative_to(ROOT)}: broken local Markdown link: {target}")
        for match in html_src.finditer(content):
            target = match.group(1).strip()
            if not local_target_exists(path, target):
                fail(f"{path.relative_to(ROOT)}: missing local image/source: {target}")


def check_svg_assets() -> None:
    assets = ROOT / "docs/assets"
    for path in sorted(assets.glob("*.svg")):
        try:
            ET.parse(path)
        except (ET.ParseError, OSError) as exc:
            fail(f"{path.relative_to(ROOT)}: invalid SVG/XML: {exc}")


def main() -> int:
    check_translations()
    check_markdown_links()
    check_svg_assets()

    if ERRORS:
        print("RELYRA project validation failed:")
        for error in ERRORS:
            print(f" - {error}")
        return 1

    en = string_resources(ROOT / "app/src/main/res/values/strings.xml")
    ar = string_resources(ROOT / "app/src/main/res/values-ar/strings.xml")
    svg_count = len(list((ROOT / "docs/assets").glob("*.svg")))
    print(f"RELYRA validation passed: {len(en)} English strings, {len(ar)} Arabic strings, {svg_count} SVG assets, local documentation links.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
