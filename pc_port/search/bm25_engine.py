import sqlite3
import re
from typing import List, Dict, Any
from pathlib import Path
from config import DB_PATH
from core.database import Database
from search.chunker import TextChunk
from core.logger import setup_logger

logger = setup_logger("BM25Engine")

class BM25Engine:
    def __init__(self, db: Database = None):
        self.db = db or Database()

    STOP_WORDS = {
        "i", "me", "my", "myself", "we", "our", "you", "your", "he", "she", "it", "its", 
        "they", "them", "what", "which", "who", "whom", "this", "that", "these", "those", 
        "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", 
        "do", "does", "did", "a", "an", "the", "and", "but", "if", "or", "because", "as", 
        "until", "while", "of", "at", "by", "for", "with", "about", "against", "between", 
        "into", "through", "during", "before", "after", "above", "below", "to", "from", 
        "up", "down", "in", "out", "on", "off", "over", "under", "again", "further", "then", 
        "once", "here", "there", "when", "where", "why", "how", "all", "any", "both", 
        "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only", 
        "own", "same", "so", "than", "too", "very", "can", "will", "just", "don", "should", 
        "now", "neeed", "need", "find", "stored", "know", "please", "tell", "show", "give", "me"
    }

    def sanitize_query(self, query: str) -> str:
        phrases = [p.strip() for p in re.findall(r'"([^"]+)"', query) if p.strip()]
        unquoted = re.sub(r'"[^"]+"', ' ', query)
        clean = re.sub(r'[^\w\s]', ' ', unquoted).strip()
        words = clean.split()
        filtered = [w for w in words if w.lower() not in self.STOP_WORDS]
        active_words = filtered if filtered else words

        tokens = [f'"{p}"' for p in phrases]
        tokens.extend([f'"{w}"' for w in active_words])
        if not tokens:
            return ""
        return " OR ".join(tokens)

    def index_chunks(self, chunks: List[TextChunk]):
        if not chunks:
            return
        with self.db.get_connection() as conn:
            file_path = chunks[0].file_path
            conn.execute("DELETE FROM doc_fts WHERE file_path = ?", (file_path,))
            
            rows = [(c.chunk_id, c.file_path, c.file_name, c.text) for c in chunks]
            conn.executemany("""
                INSERT INTO doc_fts (chunk_id, file_path, file_name, text)
                VALUES (?, ?, ?, ?)
            """, rows)
            conn.commit()

    def search(self, query: str, top_k: int = 50) -> List[Dict[str, Any]]:
        safe_query = self.sanitize_query(query)
        if not safe_query:
            return []

        results = []
        with self.db.get_connection() as conn:
            try:
                # Column weights: chunk_id=0, file_path=2.0, file_name=8.0, text=1.0
                # Using column -1 enables SQLite FTS5 to highlight whichever column matched
                cursor = conn.execute("""
                    SELECT chunk_id, file_path, file_name, text,
                           bm25(doc_fts, 0.0, 2.0, 8.0, 1.0) AS bm25_score,
                           snippet(doc_fts, -1, '<b>', '</b>', '...', 25) AS snippet
                    FROM doc_fts
                    WHERE doc_fts MATCH ?
                    ORDER BY bm25_score ASC
                    LIMIT ?
                """, (safe_query, top_k))

                for row in cursor.fetchall():
                    results.append({
                        "chunk_id": row["chunk_id"],
                        "file_path": row["file_path"],
                        "file_name": row["file_name"],
                        "text": row["text"],
                        "score": float(row["bm25_score"]),
                        "snippet": row["snippet"] or row["text"][:150]
                    })
            except sqlite3.OperationalError:
                cursor = conn.execute("""
                    SELECT chunk_id, file_path, file_name, text, 0.0 AS bm25_score, substr(text, 1, 150) AS snippet
                    FROM doc_fts
                    WHERE text LIKE ? OR file_name LIKE ?
                    LIMIT ?
                """, (f"%{query}%", f"%{query}%", top_k))
                for row in cursor.fetchall():
                    results.append({
                        "chunk_id": row["chunk_id"],
                        "file_path": row["file_path"],
                        "file_name": row["file_name"],
                        "text": row["text"],
                        "score": 0.0,
                        "snippet": row["snippet"]
                    })

        return results

    def remove_file(self, file_path: str):
        with self.db.get_connection() as conn:
            conn.execute("DELETE FROM doc_fts WHERE file_path = ?", (file_path,))
            conn.execute("DELETE FROM file_registry WHERE file_path = ?", (file_path,))
            conn.commit()
