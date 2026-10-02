import unittest
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from search.chunker import RecursiveChunker
from search.hybrid_fusion import reciprocal_rank_fusion

class TestSearchComponents(unittest.TestCase):
    def test_recursive_chunker(self):
        # Set target_tokens to 20 so a 35-word text splits into 2+ chunks
        chunker = RecursiveChunker(target_tokens=20, overlap_tokens=5)
        sample_text = (
            "Paragraph one is about database management systems and relational SQL queries. "
            "It discusses normalization, keys, and relational algebra.\n\n"
            "Paragraph two transitions to machine learning, neural networks, and deep learning architectures. "
            "It explains gradient descent and backpropagation."
        )
        chunks = chunker.chunk_text(sample_text, "test.txt", "test.txt")
        self.assertTrue(len(chunks) >= 2)
        self.assertIn("database", chunks[0].text.lower())

    def test_rrf_fusion(self):
        bm25_matches = [
            {"chunk_id": "doc1#0", "file_path": "/path/doc1.pdf", "file_name": "doc1.pdf", "text": "DBMS notes"},
            {"chunk_id": "doc2#0", "file_path": "/path/doc2.pdf", "file_name": "doc2.pdf", "text": "Operating systems"}
        ]
        vector_matches = [
            {"chunk_id": "doc2#0", "file_path": "/path/doc2.pdf", "file_name": "doc2.pdf", "text": "Operating systems"},
            {"chunk_id": "doc1#0", "file_path": "/path/doc1.pdf", "file_name": "doc1.pdf", "text": "DBMS notes"}
        ]
        fused = reciprocal_rank_fusion(bm25_matches, vector_matches, k=60)
        self.assertEqual(len(fused), 2)
        self.assertTrue(fused[0]["fused_score"] > 0)

if __name__ == "__main__":
    unittest.main()
