import unittest

from audit_accessibility import inspect_icon_buttons
from audit_sbom import classify_license, license_values


class AccessibilityTests(unittest.TestCase):
    def test_icon_only_button_with_description_passes(self):
        source = """
            IconButton(onClick = {}) {
                Icon(imageVector = icon, contentDescription = stringResource(R.string.close))
            }
        """
        count, findings = inspect_icon_buttons(source, "Test.kt")
        self.assertEqual(count, 1)
        self.assertEqual(findings, [])

    def test_icon_only_button_without_description_is_reported(self):
        source = """
            IconButton(onClick = {}) {
                Icon(imageVector = icon, contentDescription = null)
            }
        """
        count, findings = inspect_icon_buttons(source, "Test.kt")
        self.assertEqual(count, 1)
        self.assertEqual(len(findings), 1)

    def test_text_button_is_not_classified_as_icon_only(self):
        count, findings = inspect_icon_buttons(
            'IconButton(onClick = {}) { Text("Close") }', "Test.kt"
        )
        self.assertEqual(count, 0)
        self.assertEqual(findings, [])


class LicenseInventoryTests(unittest.TestCase):
    def test_missing_license_requires_review(self):
        self.assertEqual(classify_license("UNSPECIFIED"), "REVIEW_REQUIRED")

    def test_gpl_requires_review(self):
        self.assertEqual(classify_license("GPL-3.0-only"), "REVIEW_REQUIRED")

    def test_apache_identifier_is_not_legal_approval(self):
        self.assertEqual(classify_license("Apache-2.0"), "IDENTIFIER_FOUND")

    def test_license_expression_is_extracted(self):
        component = {"licenses": [{"expression": "MIT OR Apache-2.0"}]}
        self.assertEqual(license_values(component), ["MIT OR Apache-2.0"])


if __name__ == "__main__":
    unittest.main()
