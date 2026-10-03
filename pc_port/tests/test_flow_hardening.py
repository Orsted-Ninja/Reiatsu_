import unittest
import sys
import tempfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from core.database import Database
from actions.trash_manager import TrashManager
from core.system_ops import get_system_telemetry, get_standard_user_folders
from parsers.pdf_parser import PDFParser
from intelligence.llm_agent import LLMAgent

class TestFlowHardening(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(dir=str(BASE_DIR / ".cache" / "tmp")))
        self.db = Database(self.temp_dir / "test_hardening.db")
        self.trash_manager = TrashManager(trash_dir=self.temp_dir / ".trash", db=self.db)

    def test_dynamic_telemetry_and_presets(self):
        tel = get_system_telemetry()
        self.assertIn("ram_total_gb", tel)
        self.assertIn("drive_name", tel)
        self.assertTrue(tel["ram_total_gb"] > 0)

        folders = get_standard_user_folders()
        self.assertIn("Current Project", folders)
        self.assertTrue(len(folders) >= 2)

    def test_corrupt_pdf_resilience(self):
        # Create a corrupt, pseudo-PDF file with invalid bytes
        corrupt_pdf = self.temp_dir / "corrupted_file.pdf"
        corrupt_pdf.write_bytes(b"%PDF-1.4\nGarbage binary content that cannot be parsed as a valid PDF document\n%%EOF")

        parser = PDFParser()
        doc = parser.parse(corrupt_pdf)
        self.assertIsNotNone(doc)
        self.assertEqual(doc.file_name, "corrupted_file.pdf")
        # Should not crash and should contain error or empty text
        self.assertTrue(len(doc.full_text) > 0)

    def test_locked_file_resilience(self):
        # Create a test file
        test_file = self.temp_dir / "locked_document.docx"
        test_file.write_text("Secret notes in locked doc", encoding="utf-8")

        # Open file exclusively to simulate Word/Excel lock on Windows
        with open(test_file, "r+b") as locked_handle:
            # Attempt to move to trash while locked
            res = self.trash_manager.move_to_trash(test_file)
            self.assertIsNotNone(res)
            # On Windows, locked open file will trigger LOCKED status or NOT_FOUND
            if sys.platform == "win32":
                self.assertIn(res["status"], ["LOCKED", "ERROR", "TRASHED"])
            # Ensure the call did not raise an unhandled exception!

    def test_ollama_auto_detection(self):
        agent = LLMAgent()
        self.assertIsNotNone(agent.base_url)
        self.assertTrue(agent.base_url.startswith("http"))
        self.assertIsNotNone(agent.model)

    def test_rag_extractive_summary_offline(self):
        agent = LLMAgent()
        matched = [{
            "file_name": "Test_Doc.pdf",
            "file_path": "C:/docs/Test_Doc.pdf",
            "matching_chunks": ["Critical storage notes and database schemas."],
            "best_snippet": "Critical storage notes and database schemas.",
            "is_verified_identity": "resume"
        }]
        full = agent._generate_extractive_summary("database schemas", matched)
        self.assertTrue(len(full) > 20)
        self.assertIn("Test_Doc.pdf", full)
        self.assertIn("Grounded Summary", full)

    def test_rag_snippet_fallback_when_chunks_empty(self):
        agent = LLMAgent()
        matched = [{
            "file_name": "Empty_Chunks.txt",
            "file_path": "C:/docs/Empty_Chunks.txt",
            "matching_chunks": [],
            "best_snippet": "Important algorithm details on memory pooling."
        }]
        ctx = agent._extract_rich_context(matched[0])
        self.assertIn("Important algorithm details on memory pooling", ctx)

if __name__ == "__main__":
    unittest.main()
