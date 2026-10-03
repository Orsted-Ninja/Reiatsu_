import unittest
import shutil
import tempfile
from pathlib import Path
from core.database import Database
from core.identity_expander import expand_identity_query, AADHAAR_REGEX
from core.ocr_utils import extract_text_from_image
from search.hybrid_fusion import reciprocal_rank_fusion
from search.hybrid_search import HybridSearchEngine
from PIL import Image, ImageDraw

class TestIdentityAndOcr(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_identity_ocr_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_identity_expansion_terms(self):
        expanded_list, expanded_set = expand_identity_query("find my aadhar card")
        self.assertIn("aadhaar", expanded_set)
        self.assertIn("uidai", expanded_set)
        self.assertIn("adhar", expanded_set)

        expanded_pan, pan_set = expand_identity_query("where is my pan card")
        self.assertIn("nsdl", pan_set)
        self.assertIn("income tax department", pan_set)

    def test_aadhaar_regex_pattern(self):
        sample_1 = "Candidate Aadhaar: 2325 7310 3681."
        sample_2 = "Aadhaar No: 861416487861"
        sample_3 = "Phone: 9778372309 (not aadhar)"

        self.assertIsNotNone(AADHAAR_REGEX.search(sample_1))
        self.assertIsNotNone(AADHAAR_REGEX.search(sample_2))
        self.assertIsNone(AADHAAR_REGEX.search(sample_3))

    def test_saturated_rrf_scoring(self):
        # 1-page document with 1 high-relevance BM25 chunk
        bm25_matches = [
            {"chunk_id": "doc1_c1", "file_path": "/path/certificate.pdf", "file_name": "certificate.pdf", "score": -10.0, "text": "Aadhaar Card UIDAI 2325 7310 3681", "snippet": "Aadhaar Card UIDAI"}
        ]
        # Multi-page book with 15 weak vector chunks
        vector_matches = [
            {"chunk_id": f"book_c{i}", "file_path": "/path/large_textbook.pdf", "file_name": "large_textbook.pdf", "score": 0.35, "text": f"general card chapter text {i}", "snippet": "card chapter"}
            for i in range(15)
        ]

        fused = reciprocal_rank_fusion(bm25_matches, vector_matches, query="aadhar card", top_k=5)
        # Certificate should rank #1 because RRF saturates extra chunk accumulation and boosts identity terms
        self.assertEqual(fused[0]["file_name"], "certificate.pdf")

    def test_image_ocr_winocr_generation(self):
        img_path = self.temp_dir / "test_text_img.png"
        img = Image.new("RGB", (400, 100), color=(255, 255, 255))
        draw = ImageDraw.Draw(img)
        draw.text((10, 30), "GOVERNMENT OF INDIA", fill=(0, 0, 0))
        img.save(img_path)

        ocr_text = extract_text_from_image(img_path)
        # Verify OCR returns text containing the words
        self.assertTrue(len(ocr_text) > 0)
        self.assertIn("INDIA", ocr_text.upper())

if __name__ == "__main__":
    unittest.main()
