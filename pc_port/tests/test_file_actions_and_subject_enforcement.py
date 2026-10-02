import unittest
import tempfile
import os
from pathlib import Path
from core.identity_expander import get_identity_constraints, PAN_REGEX, AADHAAR_REGEX
from core.system_ops import open_file_in_os, show_in_explorer
from search.live_scanner import LiveFilesystemScanner


class TestFileActionsAndSubjectEnforcement(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.base = Path(self.temp_dir.name)
        self.scanner = LiveFilesystemScanner()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_identity_constraints_detection(self):
        # 1. PAN query
        c_pan = get_identity_constraints("what is my pan card number")
        self.assertIsNotNone(c_pan)
        self.assertEqual(c_pan["identity_type"], "PAN Card")
        self.assertIn("pan", c_pan["required_terms"])
        self.assertEqual(c_pan["regex"], PAN_REGEX)

        # 2. Aadhaar query
        c_aadhaar = get_identity_constraints("find my aadhar card")
        self.assertIsNotNone(c_aadhaar)
        self.assertEqual(c_aadhaar["identity_type"], "Aadhaar Card")
        self.assertIn("aadhar", c_aadhaar["required_terms"])
        self.assertEqual(c_aadhaar["regex"], AADHAAR_REGEX)

        # 3. Passport query
        c_pass = get_identity_constraints("where is my passport")
        self.assertIsNotNone(c_pass)
        self.assertEqual(c_pass["identity_type"], "Passport")

        # 4. Non-identity general queries must return None
        self.assertIsNone(get_identity_constraints("where are the question papers for cet415?"))
        self.assertIsNone(get_identity_constraints("find lecture notes module 4"))
        self.assertIsNone(get_identity_constraints("project presentation slides"))

    def test_pan_search_rejects_election_nic_form(self):
        # Create a file mimicking Election NIC Form.pdf which caused false positive
        election_file = self.base / "Election NIC Form.txt"
        election_file.write_text(
            "Election Commission of India. Identity Card Number: 987654. Form number 6B.\n"
            "This card is issued to all electors. Please note your serial number.",
            encoding="utf-8"
        )

        c_pan = get_identity_constraints("what is my pan card number")
        keywords = ["pan", "card", "number", "income tax department", "utiitsl", "nsdl"]
        phrases = ["pan card", "card number"]

        # Content inspection must strictly return None (disqualified)
        result = self.scanner._inspect_file_content(
            election_file,
            keywords=keywords,
            phrases=phrases,
            identity_constraint=c_pan
        )
        self.assertIsNone(result, "Election NIC Form should be completely rejected for PAN searches!")

    def test_pan_search_accepts_authentic_pan_file(self):
        # Create a file with authentic PAN details
        pan_file = self.base / "My_Tax_Record.txt"
        pan_file.write_text(
            "Income Tax Department\n"
            "Govt. of India\n"
            "Permanent Account Number Card\n"
            "Name: Rahul Sharma\n"
            "Father: K. Sharma\n"
            "PAN: ABCDE1234F\n",
            encoding="utf-8"
        )

        c_pan = get_identity_constraints("what is my pan card number")
        keywords = ["pan", "card", "number", "income tax department", "utiitsl", "nsdl"]
        phrases = ["pan card", "card number"]

        result = self.scanner._inspect_file_content(
            pan_file,
            keywords=keywords,
            phrases=phrases,
            identity_constraint=c_pan
        )
        self.assertIsNotNone(result)
        score, snippet = result
        self.assertGreaterEqual(score, 0.09)
        self.assertTrue("ABCDE1234F" in snippet or "PAN" in snippet)

    def test_aadhaar_search_enforcement(self):
        # Random credit card statement should be rejected
        cc_file = self.base / "Credit_Card_Statement.txt"
        cc_file.write_text(
            "HDFC Bank Credit Card Statement.\n"
            "Card Number ending in 4321. Total amount due: 15,000 INR.",
            encoding="utf-8"
        )

        c_aadhaar = get_identity_constraints("find my aadhar card")
        keywords = ["aadhar", "card", "aadhaar", "uidai"]
        phrases = ["aadhar card"]

        res_cc = self.scanner._inspect_file_content(
            cc_file,
            keywords=keywords,
            phrases=phrases,
            identity_constraint=c_aadhaar
        )
        self.assertIsNone(res_cc, "Credit card statement must be rejected for Aadhaar query!")

        # Authentic Aadhaar file
        aadhaar_file = self.base / "Resident_ID.txt"
        aadhaar_file.write_text(
            "Unique Identification Authority of India (UIDAI)\n"
            "Government of India\n"
            "Aadhaar Number: 3456 7890 1234\n",
            encoding="utf-8"
        )

        res_uid = self.scanner._inspect_file_content(
            aadhaar_file,
            keywords=keywords,
            phrases=phrases,
            identity_constraint=c_aadhaar
        )
        self.assertIsNotNone(res_uid)
        score, snippet = res_uid
        self.assertGreaterEqual(score, 0.09)
        self.assertIn("3456 7890 1234", snippet)

    def test_alphanumeric_code_expansion(self):
        # Query like "cet415" must expand into "cet 415" and "cet-415"
        phrases, keywords = self.scanner._extract_query_terms("where are the question papers for cet415?")
        self.assertTrue("cet 415" in phrases or "cet-415" in phrases)
        self.assertIn("cet", keywords)
        self.assertIn("415", keywords)

    def test_system_ops_robustness(self):
        # Test non-existent file handling
        fake_path = "F:/non_existent_folder_xyz/fake_file.pdf"
        self.assertFalse(open_file_in_os(fake_path))
        self.assertFalse(show_in_explorer(fake_path))

        # Test existing file handling
        real_file = self.base / "test_doc.txt"
        real_file.write_text("Test content", encoding="utf-8")
        # Ensure show_in_explorer returns True for valid existing file
        self.assertTrue(show_in_explorer(str(real_file)))


if __name__ == "__main__":
    unittest.main()
