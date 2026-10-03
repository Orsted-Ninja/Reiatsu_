import unittest
import tempfile
import shutil
from pathlib import Path
from core.database import Database
from search.embedder import TextEmbedder
from search.vector_engine import VectorEngine
from search.hybrid_search import HybridSearchEngine
from search.live_scanner import LiveFilesystemScanner

class TestTargetedSearchAndChunkGovernance(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_chunk_gov_"))
        self.db_path = self.temp_dir / "test.db"
        self.chroma_dir = self.temp_dir / "chroma"
        self.db = Database(db_path=self.db_path)
        self.embedder = TextEmbedder()
        self.vector_engine = VectorEngine(chroma_dir=self.chroma_dir)
        self.search_engine = HybridSearchEngine(self.db, self.embedder, self.vector_engine)

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_first_page_excerpt_matching(self):
        # Create an unindexed document with a realistic first-page excerpt
        first_page_text = (
            "National Institute of Technology Calicut. Department of Computer Science and Engineering. "
            "Advanced Distributed Cloud Computing and Virtualized Systems Laboratory. "
            "Course Code: CET415 Deep Storage Architecture and Hierarchical Indexing. "
            "Mid-semester examination question paper and evaluation guidelines."
        )
        test_file = self.temp_dir / "exam_evaluation_CET415.txt"
        test_file.write_text(first_page_text, encoding="utf-8")

        # Query with multi-word excerpt from the first page
        query = "Advanced Distributed Cloud Computing and Virtualized Systems Laboratory CET415"
        matches = self.search_engine.live_scanner.scan_live(
            query=query,
            target_dirs=[self.temp_dir],
            timeout_seconds=5.0
        )

        self.assertGreaterEqual(len(matches), 1)
        top = matches[0]
        self.assertEqual(top["file_name"], "exam_evaluation_CET415.txt")
        self.assertTrue(top["is_live_discovery"])
        self.assertFalse(top.get("is_chunked", False))
        self.assertGreater(top["fused_score"], 0.07)
        self.assertIn("virtualized systems", top["best_snippet"].lower())

    def test_live_only_mode_bypasses_indexed_chunks(self):
        # 1. Index document A into SQLite & Chroma
        doc_a = self.temp_dir / "indexed_document_a.txt"
        doc_a.write_text("Quantum computing algorithms and supercomputers.", encoding="utf-8")
        self.search_engine.index_file(doc_a)
        self.assertTrue(self.search_engine.is_file_chunked(doc_a)["is_chunked"])

        # 2. Create unindexed document B
        doc_b = self.temp_dir / "unindexed_document_b.txt"
        doc_b.write_text("Quantum computing gate teleportation and error mitigation.", encoding="utf-8")

        # 3. Search with live_only mode targeting temp_dir
        results, trace = self.search_engine.search_with_trace(
            query="Quantum computing gate teleportation",
            target_dirs=[self.temp_dir],
            search_mode="live_only"
        )

        self.assertEqual(trace["search_mode"], "live_only")
        self.assertEqual(trace["bm25_hits"], 0)
        self.assertEqual(trace["vector_hits"], 0)
        self.assertGreaterEqual(len(results), 1)
        # Should contain doc_b, and NOT return doc_a as a chunked result
        result_paths = [r["file_path"].lower() for r in results]
        self.assertIn(str(doc_b.resolve()).lower(), result_paths)
        self.assertNotIn(str(doc_a.resolve()).lower(), result_paths)

    def test_chunk_and_unchunk_governance(self):
        doc = self.temp_dir / "governance_document.txt"
        doc.write_text("Machine learning model distillation and pruning techniques.", encoding="utf-8")

        # Initially unchunked
        status_before = self.search_engine.is_file_chunked(doc)
        self.assertFalse(status_before["is_chunked"])
        self.assertEqual(status_before["chunk_count"], 0)

        # Explicitly chunk file
        success = self.search_engine.chunk_file(doc)
        self.assertTrue(success)

        status_after_chunk = self.search_engine.is_file_chunked(doc)
        self.assertTrue(status_after_chunk["is_chunked"])
        self.assertGreaterEqual(status_after_chunk["chunk_count"], 1)

        # Explicitly unchunk file
        unchunk_success = self.search_engine.unchunk_file(doc)
        self.assertTrue(unchunk_success)

        status_after_unchunk = self.search_engine.is_file_chunked(doc)
        self.assertFalse(status_after_unchunk["is_chunked"])
        self.assertEqual(status_after_unchunk["chunk_count"], 0)

        # File on disk MUST still exist unharmed
        self.assertTrue(doc.exists())

    def test_auto_chunk_policy_default_deny(self):
        doc = self.temp_dir / "policy_test_doc.txt"
        doc.write_text("Database normalization BCNF and 3NF functional dependencies.", encoding="utf-8")

        # Search with auto_chunk=False (default)
        matches = self.search_engine.live_scanner.scan_live(
            query="Database normalization BCNF",
            target_dirs=[self.temp_dir],
            auto_chunk=False
        )
        self.assertGreaterEqual(len(matches), 1)
        
        # Verify file was NOT automatically indexed into SQLite registry
        status = self.search_engine.is_file_chunked(doc)
        self.assertFalse(status["is_chunked"])

if __name__ == "__main__":
    unittest.main()
