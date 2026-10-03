import unittest
import tempfile
import shutil
from pathlib import Path
from core.workspace_manager import WorkspaceManager, DEFAULT_WORKSPACE_DIR
from search.live_scanner import LiveFilesystemScanner
from core.identity_expander import AADHAAR_REGEX, PAN_REGEX, expand_identity_query


class TestWorkspaceManager(unittest.TestCase):
    def setUp(self):
        self.mgr = WorkspaceManager()
        self.test_dir = Path(tempfile.mkdtemp(prefix="ss_ws_test_"))

    def tearDown(self):
        try:
            self.mgr.reset_to_default()
            shutil.rmtree(self.test_dir, ignore_errors=True)
        except OSError:
            pass  # Best effort test cleanup

    def test_default_workspace(self):
        ws = self.mgr.get_workspace_dir()
        self.assertIn("pc_port", str(ws).lower())

    def test_workspace_stats(self):
        stats = self.mgr.get_workspace_stats()
        self.assertIn("workspace_dir", stats)
        self.assertIn("db_path", stats)
        self.assertIn("chroma_dir", stats)
        self.assertIn("total_workspace_size_bytes", stats)
        self.assertIn("drive_free_bytes", stats)
        self.assertGreaterEqual(stats["drive_free_bytes"], 0)

    def test_switch_and_reset_workspace(self):
        ok, msg = self.mgr.set_workspace_dir(self.test_dir, migrate_files=False)
        self.assertTrue(ok)
        self.assertEqual(self.mgr.get_workspace_dir(), self.test_dir.resolve())
        self.assertTrue((self.test_dir / "chroma_db").exists())

        # Reset to default
        ok_reset, _ = self.mgr.reset_to_default()
        self.assertTrue(ok_reset)
        self.assertEqual(self.mgr.get_workspace_dir(), DEFAULT_WORKSPACE_DIR)


class TestDeepSearchAndWordBoundaries(unittest.TestCase):
    def setUp(self):
        self.scanner = LiveFilesystemScanner()
        self.test_dir = Path(tempfile.mkdtemp(prefix="ss_scan_test_"))

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_strict_word_boundaries_no_substring_false_positives(self):
        # "card" must NOT match "discard" or "backward"
        false_file = self.test_dir / "discard_notes.txt"
        false_file.write_text("This seminar discusses how we discard stale tokens and backward propagation.", encoding="utf-8")

        res_false = self.scanner._inspect_file_content(false_file, keywords=["card"], phrases=[])
        self.assertIsNone(res_false, "Word 'card' should NOT match 'discard' or 'backward'")

        # "card" MUST match genuine word "card"
        true_file = self.test_dir / "id_card.txt"
        true_file.write_text("Please present your identity card at the verification desk.", encoding="utf-8")
        res_true = self.scanner._inspect_file_content(true_file, keywords=["card"], phrases=[])
        self.assertIsNotNone(res_true, "Word 'card' MUST match standalone word 'card'")

    def test_aadhaar_uid_regex_and_verified_detection(self):
        # Authentic Aadhaar card format
        aadhaar_text = "Government of India / Unique Identification Authority. Your Aadhaar No. 2325 7310 3681 NIRANJAN J"
        m = AADHAAR_REGEX.search(aadhaar_text)
        self.assertIsNotNone(m)
        self.assertEqual(m.group(0), "2325 7310 3681")

        # False pattern starting with 0 or 1 should NOT match per UIDAI standard
        invalid_uid = "Order ID: 0123 4567 8901 and Serial 1234 5678 9012"
        m_invalid = AADHAAR_REGEX.search("Code: 0123 4567 8901")
        self.assertIsNone(m_invalid)

    def test_pan_regex(self):
        pan_text = "Income Tax Department Permanent Account Number ABCDE1234F"
        m = PAN_REGEX.search(pan_text)
        self.assertIsNotNone(m)
        self.assertEqual(m.group(0).upper(), "ABCDE1234F")

    def test_toolchain_directories_pruned(self):
        self.assertIn("msys64", self.scanner._ignore_dirs_lower)
        self.assertIn("mingw64", self.scanner._ignore_dirs_lower)
        self.assertIn("ucrt64", self.scanner._ignore_dirs_lower)
        self.assertIn("libstdc++", self.scanner._ignore_dirs_lower)


if __name__ == "__main__":
    unittest.main()
