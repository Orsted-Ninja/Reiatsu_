import unittest
import tempfile
import warnings
from pathlib import Path
from core.database import Database
from search.hybrid_search import HybridSearchEngine
from core.system_ops import open_file_in_os, show_in_explorer
from core.coordinator import EngineCoordinator


class TestAuditHardening(unittest.TestCase):
    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="ss_audit_test_"))
        self.db = Database()
        self.engine = HybridSearchEngine(db=self.db)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_unchunk_synchronization(self):
        """Verifies unchunk completely removes file from file_registry, doc_fts, and centroids."""
        test_file = self.test_dir / "audit_doc.txt"
        test_file.write_text(
            "Quantum error correction utilizes surface codes to protect quantum bits against decoherence.",
            encoding="utf-8"
        )

        # 1. Index file
        ok = self.engine.index_file(test_file, force=True)
        self.assertTrue(ok)

        # Verify indexed state
        status_before = self.engine.is_file_chunked(test_file)
        self.assertTrue(status_before["is_chunked"])
        self.assertGreater(status_before["chunk_count"], 0)

        with self.db.get_connection() as conn:
            reg_before = conn.execute("SELECT * FROM file_registry WHERE file_path = ?", (str(test_file.resolve()),)).fetchone()
            self.assertIsNotNone(reg_before)

        # 2. Unchunk file
        unchunk_ok = self.engine.unchunk_file(test_file)
        self.assertTrue(unchunk_ok)

        # 3. Verify unchunked state
        status_after = self.engine.is_file_chunked(test_file)
        self.assertFalse(status_after["is_chunked"])
        self.assertEqual(status_after["chunk_count"], 0)

        with self.db.get_connection() as conn:
            reg_after = conn.execute("SELECT * FROM file_registry WHERE file_path = ?", (str(test_file.resolve()),)).fetchone()
            self.assertIsNone(reg_after, "File must be completely removed from file_registry upon unchunk")

            fts_after = conn.execute("SELECT * FROM doc_fts WHERE file_path = ?", (str(test_file.resolve()),)).fetchone()
            self.assertIsNone(fts_after, "File chunks must be removed from doc_fts")

            centroid_after = conn.execute("SELECT * FROM doc_centroids WHERE file_path = ?", (str(test_file.resolve()),)).fetchone()
            self.assertIsNone(centroid_after, "File centroids must be removed from doc_centroids")

        # 4. Crucial: The underlying physical file MUST still exist!
        self.assertTrue(test_file.exists(), "Unchunk must leave the physical file intact on disk")

    def test_subprocess_no_resource_warning(self):
        """Verifies opening and revealing files does not emit unhandled ResourceWarning."""
        test_file = self.test_dir / "resource_test.txt"
        test_file.write_text("Testing clean process reaping.", encoding="utf-8")

        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always", ResourceWarning)

            # Test reveal in explorer
            show_res = show_in_explorer(str(test_file))
            self.assertTrue(show_res)

            # Filter for subprocess ResourceWarnings
            subp_warnings = [w for w in caught if issubclass(w.category, ResourceWarning) and "subprocess" in str(w.message)]
            self.assertEqual(len(subp_warnings), 0, f"Found unexpected ResourceWarnings: {subp_warnings}")

    def test_get_all_chunked_files_after_unchunk(self):
        """Verifies EngineCoordinator.get_all_chunked_files immediately reflects unchunked state."""
        coord = EngineCoordinator()
        test_file = self.test_dir / "coord_unchunk_test.txt"
        test_file.write_text("Artificial intelligence reasoning in constrained local edge hardware.", encoding="utf-8")

        coord.chunk_file(test_file)
        chunked_files_before = [f["file_path"] for f in coord.get_all_chunked_files()]
        self.assertIn(str(test_file.resolve()), chunked_files_before)

        coord.unchunk_file(test_file)
        chunked_files_after = [f["file_path"] for f in coord.get_all_chunked_files()]
        self.assertNotIn(str(test_file.resolve()), chunked_files_after)


if __name__ == "__main__":
    unittest.main()
