import threading
import chromadb
from chromadb.config import Settings
from typing import List, Dict, Any, Optional
from pathlib import Path
from config import CHROMA_DIR
from search.chunker import TextChunk
from core.logger import setup_logger

logger = setup_logger("VectorEngine")

class VectorEngine:
    _instances: Dict[str, "VectorEngine"] = {}
    _class_lock = threading.RLock()

    def __new__(cls, chroma_dir: Path = CHROMA_DIR):
        resolved_path = str(Path(chroma_dir).resolve())
        if resolved_path not in cls._instances:
            with cls._class_lock:
                if resolved_path not in cls._instances:
                    inst = super(VectorEngine, cls).__new__(cls)
                    inst._initialized = False
                    cls._instances[resolved_path] = inst
        return cls._instances[resolved_path]

    def __init__(self, chroma_dir: Path = CHROMA_DIR):
        if getattr(self, "_initialized", False):
            return

        with self._class_lock:
            if getattr(self, "_initialized", False):
                return
            self._lock = threading.RLock()
            self.chroma_dir = str(Path(chroma_dir).resolve())
            self.client = chromadb.PersistentClient(
                path=self.chroma_dir,
                settings=Settings(anonymized_telemetry=False)
            )
            
            # Collection 1: Chunk-level vectors for RAG and search
            self.chunk_collection = self.client.get_or_create_collection(
                name="storagesense_chunks",
                metadata={"hnsw:space": "cosine"}
            )

            # Collection 2: Document-level centroid vectors for true semantic duplicate detection
            self.doc_collection = self.client.get_or_create_collection(
                name="storagesense_documents",
                metadata={"hnsw:space": "cosine"}
            )

            self._initialized = True

    def index_chunks(self, chunks: List[TextChunk], embeddings: List[List[float]]):
        if not chunks or not embeddings:
            return

        file_path = chunks[0].file_path
        with self._lock:
            self.remove_file(file_path)

            ids = [c.chunk_id for c in chunks]
            documents = [c.text for c in chunks]
            metadatas = [
                {
                    "file_path": c.file_path,
                    "file_name": c.file_name,
                    "chunk_index": c.chunk_index,
                    "page_number": c.page_number
                }
                for c in chunks
            ]

            self.chunk_collection.add(
                ids=ids,
                embeddings=embeddings,
                documents=documents,
                metadatas=metadatas
            )

    def index_document_centroid(self, file_path: str, file_name: str, centroid: List[float], chunk_count: int):
        """Indexes average embedding vector of document for fast semantic deduplication."""
        with self._lock:
            try:
                self.doc_collection.delete(ids=[file_path])
            except Exception as e_del:
                logger.debug(f"ChromaDB delete centroid for {file_path} skipped/failed: {e_del}")

            self.doc_collection.add(
                ids=[file_path],
                embeddings=[centroid],
                documents=[file_name],
                metadatas=[{"file_path": file_path, "file_name": file_name, "chunk_count": chunk_count}]
            )

    def search_chunks(self, query_embedding: List[float], top_k: int = 50) -> List[Dict[str, Any]]:
        count = self.chunk_collection.count()
        if count == 0:
            return []

        actual_k = min(top_k, count)
        results = self.chunk_collection.query(
            query_embeddings=[query_embedding],
            n_results=actual_k
        )

        output = []
        if results and results["ids"] and len(results["ids"][0]) > 0:
            ids = results["ids"][0]
            docs = results["documents"][0]
            metas = results["metadatas"][0]
            distances = results["distances"][0]

            for i in range(len(ids)):
                similarity = 1.0 - distances[i]
                output.append({
                    "chunk_id": ids[i],
                    "file_path": metas[i]["file_path"],
                    "file_name": metas[i]["file_name"],
                    "text": docs[i],
                    "score": float(similarity),
                    "snippet": docs[i][:150]
                })

        return output

    def find_similar_documents(self, doc_embedding: List[float], top_k: int = 10) -> List[Dict[str, Any]]:
        """Returns documents whose centroid vector is highly similar to doc_embedding."""
        count = self.doc_collection.count()
        if count == 0:
            return []

        actual_k = min(top_k, count)
        results = self.doc_collection.query(
            query_embeddings=[doc_embedding],
            n_results=actual_k
        )

        output = []
        if results and results["ids"] and len(results["ids"][0]) > 0:
            ids = results["ids"][0]
            metas = results["metadatas"][0]
            distances = results["distances"][0]

            for i in range(len(ids)):
                similarity = 1.0 - distances[i]
                output.append({
                    "file_path": ids[i],
                    "file_name": metas[i]["file_name"],
                    "similarity": float(similarity)
                })

        return output

    def find_similar_documents_batch(self, doc_embeddings: List[List[float]], top_k: int = 10) -> List[List[Dict[str, Any]]]:
        """Returns documents whose centroid vector is highly similar for multiple queries in batch."""
        count = self.doc_collection.count()
        if count == 0 or not doc_embeddings:
            return [[] for _ in doc_embeddings]

        actual_k = min(top_k, count)
        results = self.doc_collection.query(
            query_embeddings=doc_embeddings,
            n_results=actual_k
        )

        batch_output = []
        if results and results["ids"]:
            for batch_idx in range(len(results["ids"])):
                ids = results["ids"][batch_idx]
                metas = results["metadatas"][batch_idx]
                distances = results["distances"][batch_idx]
                
                output = []
                for i in range(len(ids)):
                    similarity = 1.0 - distances[i]
                    output.append({
                        "file_path": ids[i],
                        "file_name": metas[i]["file_name"],
                        "similarity": float(similarity)
                    })
                batch_output.append(output)
        
        return batch_output

    def remove_file(self, file_path: str):
        with self._lock:
            try:
                self.chunk_collection.delete(where={"file_path": file_path})
            except Exception as e_chunk:
                logger.debug(f"ChromaDB delete chunks for {file_path} skipped/failed: {e_chunk}")
            try:
                self.doc_collection.delete(ids=[file_path])
            except Exception as e_doc:
                logger.debug(f"ChromaDB delete doc for {file_path} skipped/failed: {e_doc}")
