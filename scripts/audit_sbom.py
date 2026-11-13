#!/usr/bin/env python3
"""Build a review-oriented license inventory from CycloneDX JSON SBOM files."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REVIEW_LICENSE = re.compile(
    r"\b(?:AGPL|GPL|LGPL|MPL|CDDL|EPL|SSPL|BUSL|OSL|EUPL|CPAL)\b|CC-BY-SA",
    re.IGNORECASE,
)


def license_values(component: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for entry in component.get("licenses", []) or []:
        if not isinstance(entry, dict):
            continue
        item = entry.get("license")
        if isinstance(item, dict):
            value = item.get("id") or item.get("name")
            if value:
                values.append(str(value).strip())
        expression = entry.get("expression")
        if expression:
            values.append(str(expression).strip())
    return sorted(set(value for value in values if value)) or ["UNSPECIFIED"]


def classify_license(value: str) -> str:
    if value == "UNSPECIFIED" or value.upper() in {"NOASSERTION", "UNKNOWN"}:
        return "REVIEW_REQUIRED"
    if REVIEW_LICENSE.search(value):
        return "REVIEW_REQUIRED"
    return "IDENTIFIER_FOUND"


def read_sbom(path: Path) -> list[dict[str, Any]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("bomFormat") != "CycloneDX":
        raise ValueError(f"{path}: expected a CycloneDX document")
    rows: list[dict[str, Any]] = []
    for component in document.get("components", []) or []:
        if not isinstance(component, dict):
            continue
        name = str(component.get("name") or "").strip()
        if not name:
            continue
        for value in license_values(component):
            rows.append({
                "source": path.as_posix(),
                "group": str(component.get("group") or ""),
                "name": name,
                "version": str(component.get("version") or "unspecified"),
                "purl": str(component.get("purl") or ""),
                "license": value,
                "classification": classify_license(value),
            })
    return rows


def markdown_report(rows: list[dict[str, Any]]) -> str:
    unique = {
        (row["purl"] or row["group"] + ":" + row["name"], row["version"])
        for row in rows
    }
    review_count = sum(row["classification"] == "REVIEW_REQUIRED" for row in rows)
    lines = [
        "# RELYRA dependency license inventory",
        "",
        "Generated from the CycloneDX SBOM artifacts produced by the Phase 9 workflow.",
        "This is an automated metadata inventory, not legal advice or a compatibility decision.",
        "",
        f"- Components inventoried: {len(unique)}",
        f"- License records: {len(rows)}",
        f"- Records requiring human review: {review_count}",
        "",
        "| Component | Version | License metadata | Triage | SBOM source |",
        "|---|---:|---|---|---|",
    ]
    for row in sorted(rows, key=lambda item: (
        item["name"].lower(), item["version"], item["license"]
    )):
        component = (row["group"] + ":" if row["group"] else "") + row["name"]
        def clean(value: str) -> str:
            return value.replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {clean(component)} | {clean(row['version'])} | {clean(row['license'])} "
            f"| {row['classification']} | {clean(row['source'])} |"
        )
    lines.extend([
        "",
        "## Review rules",
        "",
        "- Missing or unrecognized license metadata is marked REVIEW_REQUIRED.",
        "- Reciprocal/copyleft families (including GPL, AGPL, LGPL, MPL, CDDL, EPL, SSPL and EUPL) are marked REVIEW_REQUIRED.",
        "- IDENTIFIER_FOUND means only that metadata exists; it is not a legal compatibility approval.",
        "- Resolve review items against upstream license texts, notices, linking model and intended distribution before publishing.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", action="append", required=True)
    parser.add_argument("--output-dir", default="security-reports/licenses")
    args = parser.parse_args()

    rows: list[dict[str, Any]] = []
    for raw_path in args.sbom:
        path = Path(raw_path)
        if not path.is_file():
            raise SystemExit(f"SBOM not found: {path}")
        rows.extend(read_sbom(path))
    if not rows:
        raise SystemExit("No components were found in the supplied SBOM files.")

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "license-inventory.json").write_text(
        json.dumps({"schemaVersion": 1, "records": rows}, indent=2) + "\n",
        encoding="utf-8",
    )
    (output / "license-inventory.md").write_text(
        markdown_report(rows), encoding="utf-8"
    )
    review_count = sum(row["classification"] == "REVIEW_REQUIRED" for row in rows)
    print(f"License inventory created: {len(rows)} records; {review_count} need human review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
