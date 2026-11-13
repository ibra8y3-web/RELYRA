import json
import tempfile
import unittest
from pathlib import Path

from audit_go_vulns import (
    DHT_ADVISORY,
    build_report,
    finding_ids,
    read_json_stream,
    semver_tuple,
    triage_dht_advisory,
)


class GoVulnerabilityTriageTests(unittest.TestCase):
    def test_semver_comparison(self):
        self.assertEqual(semver_tuple("v0.20.0"), (0, 20, 0))
        self.assertEqual(semver_tuple("v0.43.0"), (0, 43, 0))
        self.assertIsNone(semver_tuple("latest"))

    def test_dht_advisory_current_version_requires_manual_adjudication(self):
        status, reason = triage_dht_advisory({
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
            "Version": "v0.43.0",
        })
        self.assertEqual(status, "REVIEW_REQUIRED")
        self.assertIn("sources conflict", reason)
        self.assertIn("no known fix", reason)

    def test_dht_advisory_old_version_remains_affected(self):
        status, _ = triage_dht_advisory({
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
            "Version": "v0.20.0",
        })
        self.assertEqual(status, "AFFECTED")

    def test_missing_version_does_not_pass(self):
        status, _ = triage_dht_advisory({
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
        })
        self.assertEqual(status, "UNRESOLVED")

    def test_unknown_advisory_fails(self):
        report, unresolved = build_report(["GO-2026-9999"], {
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
            "Version": "v0.43.0",
        })
        self.assertTrue(unresolved)
        self.assertIn("GO-2026-9999", report)

    def test_known_advisory_does_not_pass_with_conflicting_sources(self):
        report, unresolved = build_report([DHT_ADVISORY], {
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
            "Version": "v0.43.0",
        })
        self.assertTrue(unresolved)
        self.assertIn("REVIEW_REQUIRED", report)

    def test_dht_advisory_stays_visible_even_when_govulncheck_has_no_finding(self):
        report, unresolved = build_report([], {
            "Path": "github.com/libp2p/go-libp2p-kad-dht",
            "Version": "v0.43.0",
        })
        self.assertTrue(unresolved)
        self.assertIn(DHT_ADVISORY, report)
        self.assertIn("REVIEW_REQUIRED", report)

    def test_json_stream_reads_findings(self):
        messages = [
            {"config": {"protocol_version": "v1.0.0"}},
            {"finding": {"osv": DHT_ADVISORY, "trace": []}},
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "govulncheck.json"
            path.write_text("\n".join(json.dumps(m) for m in messages), encoding="utf-8")
            parsed = read_json_stream(path)
        self.assertEqual(finding_ids(parsed), [DHT_ADVISORY])


if __name__ == "__main__":
    unittest.main()
