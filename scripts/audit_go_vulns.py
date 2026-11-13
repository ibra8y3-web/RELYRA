#!/usr/bin/env python3
"""Review govulncheck JSON findings and explicitly retain unresolved DHT advisories."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

DHT_MODULE = "github.com/libp2p/go-libp2p-kad-dht"
DHT_ADVISORY = "GO-2024-3218"
DHT_MAX_AFFECTED = (0, 20, 0)
DHT_REFERENCE = "https://github.com/advisories/GHSA-mqr9-hjr8-2m9w"


def semver_tuple(value: str) -> tuple[int, int, int] | None:
    value = value.strip().lstrip("v")
    value = value.split("+", 1)[0].split("-", 1)[0]
    parts = value.split(".")
    if len(parts) < 3:
        return None
    try:
        return tuple(int(part) for part in parts[:3])  # type: ignore[return-value]
    except ValueError:
        return None


def read_json_stream(path: Path) -> list[dict[str, Any]]:
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        raise ValueError("govulncheck JSON stream is empty")
    if raw.startswith("["):
        parsed = json.loads(raw)
        if not isinstance(parsed, list):
            raise ValueError("expected a JSON message array")
        return [item for item in parsed if isinstance(item, dict)]
    messages: list[dict[str, Any]] = []
    for line_number, line in enumerate(raw.splitlines(), 1):
        if not line.strip():
            continue
        item = json.loads(line)
        if not isinstance(item, dict):
            raise ValueError(f"message {line_number} is not a JSON object")
        messages.append(item)
    if not messages:
        raise ValueError("govulncheck JSON stream contains no messages")
    return messages


def finding_ids(messages: list[dict[str, Any]]) -> list[str]:
    ids: set[str] = set()
    for message in messages:
        finding = message.get("finding")
        if isinstance(finding, dict):
            advisory = finding.get("osv")
            if isinstance(advisory, str) and advisory:
                ids.add(advisory)
    return sorted(ids)


def triage_dht_advisory(module_info: dict[str, Any]) -> tuple[str, str]:
    if module_info.get("Path") != DHT_MODULE:
        return "UNRESOLVED", "The resolved DHT module path did not match the reviewed module."
    version = module_info.get("Version")
    if not isinstance(version, str):
        return "UNRESOLVED", "The resolved DHT module version is missing."
    parsed = semver_tuple(version)
    if parsed is None:
        return "UNRESOLVED", f"Could not parse resolved DHT module version {version!r}."
    if parsed <= DHT_MAX_AFFECTED:
        return "AFFECTED", (
            f"{DHT_MODULE}@{version} is within the GitHub advisory's stated affected range "
            "(through v0.20.0)."
        )
    return "REVIEW_REQUIRED", (
        f"{DHT_MODULE}@{version} is newer than the GitHub advisory's stated affected range "
        "(through v0.20.0), but the official Go vulnerability database reports all versions "
        "as affected with no known fix. These sources conflict; the newer version alone "
        f"does not clear this finding. Maintainer confirmation or a verifiable architectural "
        f"mitigation is required. GitHub advisory: {DHT_REFERENCE}."
    )


def build_report(ids: list[str], module_info: dict[str, Any]) -> tuple[str, bool]:
    lines = [
        "# Go vulnerability triage",
        "",
        "Generated from govulncheck JSON output and the resolved Go module graph.",
        "Unknown findings fail this gate; known conflicting advisories remain review-required even when govulncheck reports no reachable path.",
        "",
    ]
    unresolved = False
    reviewed_ids = set(ids)
    # Keep this known advisory visible independently of govulncheck reachability.
    # A missing finding is not proof that Kademlia lookup cannot be censored by Sybil peers.
    if module_info.get("Path") == DHT_MODULE:
        reviewed_ids.add(DHT_ADVISORY)
    if not reviewed_ids:
        lines.append("- Result: no govulncheck findings.")
    for advisory in sorted(reviewed_ids):
        if advisory == DHT_ADVISORY:
            status, reason = triage_dht_advisory(module_info)
            version = module_info.get("Version", "unknown")
            lines.append(f"- {advisory}: **{status}** for {DHT_MODULE}@{version}. {reason}")
            if status != "NOT_APPLICABLE":
                unresolved = True
        else:
            lines.append(f"- {advisory}: **UNRESOLVED**. No version-scoped review rule exists.")
            unresolved = True
    lines.extend([
        "",
        "## Residual design risk",
        "",
        "The package advisory concerns Sybil peers censoring Kademlia content-routing records. ",
        "The unresolved DHT advisory remains open regardless of govulncheck reachability. Its scope does not certify a public DHT against generic Sybil or censorship attacks. ",
        "RELYRA records are signature-validated, and the DHT configuration uses public-query/routing filters and peer-diversity limits; ",
        "multi-operator deployment and independent-node interoperability remain operational requirements.",
        "",
    ])
    return "\n".join(lines), unresolved


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--govulncheck-json", required=True)
    parser.add_argument("--dht-module-json", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    messages = read_json_stream(Path(args.govulncheck_json))
    module_info = json.loads(Path(args.dht_module_json).read_text(encoding="utf-8"))
    if not isinstance(module_info, dict):
        raise SystemExit("DHT module metadata is not a JSON object")
    ids = finding_ids(messages)
    report, unresolved = build_report(ids, module_info)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding="utf-8")
    print(report)
    return 1 if unresolved else 0


if __name__ == "__main__":
    raise SystemExit(main())
