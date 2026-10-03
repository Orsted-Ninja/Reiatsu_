import unittest
import tempfile
import shutil
import time
from pathlib import Path
from core.database import Database
from search.live_scanner import LiveFilesystemScanner
from search.hybrid_search import HybridSearchEngine

class TestLiveScanner(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_live_scanner_"))
        self.db_path = self.temp_dir / "test.db"
        self.db = Database(db_path=self.db_path)
        self.scanner = LiveFilesystemScanner(self.db)

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_live_discovery_unindexed_weird_name(self):
        # Create an unindexed file with a completely unrelated/obscure name
        weird_file = self.temp_dir / "temp_x99_random_data_774.txt"
        weird_file.write_text(
            "Confidential astrophysics research report: CET415 gravitational wave telemetry and sensors.",
            encoding="utf-8"
        )

        # Run live scan directly targeting the test directory
        matches = self.scanner.scan_live(
            query="CET415 gravitational wave",
            target_dirs=[self.temp_dir],
            timeout_seconds=3.0
        )

        self.assertGreaterEqual(len(matches), 1)
        top = matches[0]
        self.assertEqual(top["file_name"], "temp_x99_random_data_774.txt")
        self.assertTrue(top["is_live_discovery"])
        self.assertGreater(top["fused_score"], 0.05)
        self.assertIn("gravitational wave", top["best_snippet"].lower())

    def test_live_discovery_filename_match(self):
        # Create an unindexed file matching by filename
        doc_file = self.temp_dir / "CET415_Question_Bank_2026.txt"
        doc_file.write_text("General exam guidelines.", encoding="utf-8")

        matches = self.scanner.scan_live(
            query="CET415 questions",
            target_dirs=[self.temp_dir],
            timeout_seconds=3.0
        )

        self.assertGreaterEqual(len(matches), 1)
        top = matches[0]
        self.assertEqual(top["file_name"], "CET415_Question_Bank_2026.txt")
        self.assertTrue(top["is_live_discovery"])
        self.assertGreater(top["fused_score"], 0.05)

    def test_already_indexed_file_is_skipped_by_live_scanner(self):
        # Register a file in database as already indexed
        indexed_file = self.temp_dir / "indexed_document.txt"
        indexed_file.write_text("CET415 notes already processed.", encoding="utf-8")

        with self.db.get_connection() as conn:
            conn.execute("""
                INSERT INTO file_registry (file_path, file_name, extension, file_size, last_modified, content_hash)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (str(indexed_file.resolve()), indexed_file.name, ".txt", 100, 1000.0, "fake_hash"))
            conn.commit()

        matches = self.scanner.scan_live(
            query="CET415 notes",
            target_dirs=[self.temp_dir],
            timeout_seconds=3.0
        )

        # Should be empty because it is already indexed in file_registry
        self.assertEqual(len(matches), 0)

if __name__ == "__main__":
    unittest.main()
