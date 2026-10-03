import unittest
import sys
import tempfile
import shutil
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from core.database import Database
from search.hybrid_search import HybridSearchEngine
from intelligence.dedup_engine import DuplicateDetector

from search.vector_engine import VectorEngine

class TestSemanticDeduplication(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(dir=str(BASE_DIR / ".cache" / "tmp")))
        self.db = Database(self.temp_dir / "test.db")
        self.vector_engine = VectorEngine(chroma_dir=self.temp_dir / "chroma")
        self.search_engine = HybridSearchEngine(db=self.db, vector_engine=self.vector_engine)
        self.detector = DuplicateDetector(db=self.db, search_engine=self.search_engine, vector_engine=self.vector_engine)
        self.indexed_paths = []

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_semantic_document_duplicates(self):
        f1 = self.temp_dir / "Database_Assignment_Draft.txt"
        f1.write_text(
            "Database Management Systems Assignment. Topic: 3NF and BCNF normalization. "
            "A relation is in third normal form if every non-prime attribute is non-transitively dependent on candidate keys. "
            "Boyce Codd Normal Form is a stricter version where every determinant must be a candidate key.",
            encoding="utf-8"
        )

        f2 = self.temp_dir / "DBMS_Assignment_Final_Submitted.txt"
        f2.write_text(
            "Database Systems Assignment Final Version. Focus: Relational Normalization (3NF & BCNF). "
            "For a relation schema to satisfy 3NF, non-key attributes cannot transitively depend on primary keys. "
            "BCNF requires that for all functional dependencies X -> Y, X must be a superkey of the schema.",
            encoding="utf-8"
        )

        f3 = self.temp_dir / "Chocolate_Cake_Recipe.txt"
        f3.write_text(
            "Delicious Chocolate Cake Recipe. Mix flour, cocoa powder, sugar, baking soda, and eggs. "
            "Bake at 350 degrees Fahrenheit for 35 minutes until a toothpick inserted in the center comes out clean.",
            encoding="utf-8"
        )

        self.indexed_paths = [f1, f2, f3]
        self.search_engine.index_file(f1)
        self.search_engine.index_file(f2)
        self.search_engine.index_file(f3)

        groups = self.detector.find_semantic_document_duplicates(threshold=0.80)
        self.assertTrue(len(groups) >= 1)

        matched_group = groups[0]
        self.assertEqual(matched_group.match_type, "SEMANTIC_TEXT")
        file_names = [f["file_name"] for f in matched_group.files]
        self.assertIn("Database_Assignment_Draft.txt", file_names)
        self.assertIn("DBMS_Assignment_Final_Submitted.txt", file_names)
        self.assertNotIn("Chocolate_Cake_Recipe.txt", file_names)

    def test_exact_duplicates_with_protection(self):
        # Create exact duplicate files
        content = "CONFIDENTIAL TAX RETURN 2024 - INCOME AND EXPENSE DISCLOSURE"
        f_prot = self.temp_dir / "official_tax_return.txt"
        f_prot.write_text(content, encoding="utf-8")

        f_copy = self.temp_dir / "temp_copy_backup.txt"
        f_copy.write_text(content, encoding="utf-8")

        self.search_engine.index_file(f_prot)
        self.search_engine.index_file(f_copy)

        exact_groups = self.detector.find_exact_duplicates()
        self.assertEqual(len(exact_groups), 1)

        g = exact_groups[0]
        self.assertEqual(g.match_type, "EXACT_HASH")
        self.assertEqual(g.confidence, 1.0)
        # Verify protected document (tax keyword) is selected as KEEP
        self.assertEqual(Path(g.recommended_keep_path).name, "official_tax_return.txt")
        self.assertIn(str(f_copy.resolve()), [str(Path(p).resolve()) for p in g.candidates_to_delete])

    def test_image_duplicates(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")

        img1_path = self.temp_dir / "photo_original.png"
        img2_path = self.temp_dir / "photo_resized_copy.png"

        img1 = Image.new("RGB", (100, 100), color=(120, 200, 50))
        img1.save(str(img1_path))

        img2 = Image.new("RGB", (100, 100), color=(120, 200, 52))  # Near-identical color
        img2.save(str(img2_path))

        self.search_engine.index_file(img1_path)
        self.search_engine.index_file(img2_path)

        img_groups = self.detector.find_image_duplicates(max_hamming_distance=6)
        self.assertTrue(len(img_groups) >= 1)
        self.assertEqual(img_groups[0].match_type, "PERCEPTUAL_IMAGE")

    def test_scan_folder_for_dedup(self):
        scan_folder = self.temp_dir / "subfolder_to_scan"
        scan_folder.mkdir(parents=True, exist_ok=True)
        (scan_folder / "file_a.txt").write_text("Hello from file A in subfolder", encoding="utf-8")
        (scan_folder / "file_b.txt").write_text("Hello from file B in subfolder", encoding="utf-8")

        count = self.detector.scan_folder_for_dedup(scan_folder)
        self.assertEqual(count, 2)

if __name__ == "__main__":
    unittest.main()
