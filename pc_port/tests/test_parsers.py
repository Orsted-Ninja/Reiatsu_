import unittest
import sys
from pathlib import Path

# Add project root to sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from parsers.extractor_factory import ExtractorFactory
from core.hash_utils import compute_file_hash

class TestParsers(unittest.TestCase):
    def setUp(self):
        self.factory = ExtractorFactory()
        self.test_pdf = Path(r"F:\ASCENT\idea\StorageSense.pdf")

    def test_pdf_parsing(self):
        if not self.test_pdf.exists():
            self.skipTest("StorageSense.pdf not found")

        doc = self.factory.parse_file(self.test_pdf)
        self.assertIsNotNone(doc)
        self.assertEqual(doc.extension, ".pdf")
        self.assertTrue(len(doc.full_text) > 100)
        self.assertFalse(doc.is_scanned)
        self.assertTrue(len(doc.content_hash) == 64)

    def test_text_hashing(self):
        test_file = Path(r"F:\ASCENT\idea\plan_project.md")
        if not test_file.exists():
            self.skipTest("plan_project.md not found")

        h1 = compute_file_hash(test_file)
        h2 = compute_file_hash(test_file)
        self.assertEqual(h1, h2)
        self.assertEqual(len(h1), 64)

if __name__ == "__main__":
    unittest.main()
