#!/usr/bin/env python3
"""Heuristic accessibility triage for Compose icon buttons and clickable images.

This source scan is intentionally conservative and is not an accessibility certification.
"""
from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def skip_literal_or_comment(source: str, index: int) -> int:
    if source.startswith("//", index):
        end = source.find("\n", index)
        return len(source) if end < 0 else end + 1
    if source.startswith("/*", index):
        depth = 1
        pos = index + 2
        while pos < len(source) and depth:
            if source.startswith("/*", pos):
                depth += 1
                pos += 2
            elif source.startswith("*/", pos):
                depth -= 1
                pos += 2
            else:
                pos += 1
        return pos
    if source.startswith('"""', index):
        end = source.find('"""', index + 3)
        return len(source) if end < 0 else end + 3
    if source[index] in {'"', "'"}:
        quote = source[index]
        pos = index + 1
        while pos < len(source):
            if source[pos] == "\\":
                pos += 2
            elif source[pos] == quote:
                return pos + 1
            else:
                pos += 1
        return len(source)
    return index


def matching_delimiter(source: str, start: int, opening: str, closing: str) -> int | None:
    depth = 0
    pos = start
    while pos < len(source):
        skipped = skip_literal_or_comment(source, pos)
        if skipped != pos:
            pos = skipped
            continue
        if source[pos] == opening:
            depth += 1
        elif source[pos] == closing:
            depth -= 1
            if depth == 0:
                return pos
        pos += 1
    return None


def inspect_icon_buttons(source: str, relative_path: str) -> tuple[int, list[str]]:
    inspected = 0
    findings: list[str] = []
    for match in re.finditer(r"\bIconButton\s*\(", source):
        opening_paren = source.find("(", match.start())
        closing_paren = matching_delimiter(source, opening_paren, "(", ")")
        if closing_paren is None:
            continue
        pos = closing_paren + 1
        while pos < len(source) and source[pos].isspace():
            pos += 1
        if pos >= len(source) or source[pos] != "{":
            continue
        closing_brace = matching_delimiter(source, pos, "{", "}")
        if closing_brace is None:
            continue
        body = source[pos + 1:closing_brace]
        if not re.search(r"\bIcon\s*\(", body) or re.search(r"\bText\s*\(", body):
            continue
        inspected += 1
        descriptions = re.findall(
            r"\bcontentDescription\s*=\s*(null|[^,\n)]+)", body
        )
        if not descriptions or all(value.strip() == "null" for value in descriptions):
            line = source.count("\n", 0, match.start()) + 1
            findings.append(
                f"{relative_path}:{line}: icon-only IconButton has no non-null contentDescription"
            )
    return inspected, findings


def inspect_xml_images() -> list[str]:
    findings: list[str] = []
    resource_root = ROOT / "app/src/main/res"
    for path in resource_root.rglob("*.xml"):
        if "layout" not in path.parts:
            continue
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            findings.append(f"{path.relative_to(ROOT)}: XML parse failure: {exc}")
            continue
        for node in root.iter():
            tag = node.tag.rsplit("}", 1)[-1]
            clickable = node.attrib.get(ANDROID_NS + "clickable") == "true"
            if tag == "ImageButton" or (tag == "ImageView" and clickable):
                description = node.attrib.get(ANDROID_NS + "contentDescription")
                if not description or description == "@null":
                    findings.append(
                        f"{path.relative_to(ROOT)}: clickable {tag} has no contentDescription"
                    )
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="security-reports/accessibility")
    args = parser.parse_args()

    files = sorted((ROOT / "app/src/main/java").rglob("*.kt"))
    inspected = 0
    findings: list[str] = []
    for path in files:
        source = path.read_text(encoding="utf-8")
        count, issues = inspect_icon_buttons(
            source, path.relative_to(ROOT).as_posix()
        )
        inspected += count
        findings.extend(issues)
    findings.extend(inspect_xml_images())

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    lines = [
        "# RELYRA accessibility static triage",
        "",
        f"- Kotlin source files inspected: {len(files)}",
        f"- Icon-only Compose IconButton blocks inspected: {inspected}",
        f"- Potential labeling findings: {len(findings)}",
        "",
        "## Potential findings",
        "",
    ]
    lines.extend(
        f"- {item}" for item in findings
    ) if findings else lines.append("- No unlabeled icon-only controls detected by this heuristic.")
    lines.extend([
        "",
        "## Manual audit still required",
        "",
        "- TalkBack traversal, announcements, focus restoration and editable-field labels.",
        "- Contrast in light/dark themes, disabled/error states and non-text controls.",
        "- Font scaling, RTL reading order, keyboard/switch access and actual touch-target geometry.",
        "",
        "This script is a source-level heuristic. A clean report is not an accessibility certification.",
        "",
    ])
    (output / "accessibility-static-audit.md").write_text(
        "\n".join(lines), encoding="utf-8"
    )
    print(f"Accessibility triage: {inspected} icon-only controls, {len(findings)} potential findings.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
