import threading
from typing import List, Optional
from pathlib import Path
from config import BASE_DIR
from core.logger import setup_logger

logger = setup_logger("TextEmbedder")

class TextEmbedder:
    _instance = None
    _model = None
    _lock = threading.RLock()

    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super(TextEmbedder, cls).__new__(cls)
                    cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        with self._lock:
            if getattr(self, "_initialized", False):
                return
            self._model = None
            self._initialized = True

    def _get_device(self) -> str:
        try:
            import torch
            if torch.cuda.is_available():
                return "cuda"
            if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                return "mps"
        except Exception as e_dev:
            logger.debug(f"Hardware acceleration check skipped/failed: {e_dev}")
        return "cpu"

    def _get_model(self):
        if self._model is None:
            with self._lock:
                if self._model is None:
                    device = self._get_device()
                    logger.info(f"Initializing SentenceTransformer embedding model on demand (Device: {device})...")
                    from sentence_transformers import SentenceTransformer
                    model_dir = BASE_DIR / ".cache" / "models" / "all-MiniLM-L6-v2"
                    if model_dir.exists():
                        self._model = SentenceTransformer(str(model_dir), device=device)
                    else:
                        self._model = SentenceTransformer("all-MiniLM-L6-v2", device=device)
        return self._model

    def embed_text(self, text: str) -> List[float]:
        model = self._get_model()
        vector = model.encode(text, normalize_embeddings=True)
        return vector.tolist()

    def embed_batch(self, texts: List[str], batch_size: int = 32) -> List[List[float]]:
        if not texts:
            return []
        model = self._get_model()
        vectors = model.encode(texts, batch_size=batch_size, normalize_embeddings=True, show_progress_bar=False)
        return vectors.tolist()
