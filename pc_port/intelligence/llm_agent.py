import requests
import json
import re
import time
from pathlib import Path
from typing import Dict, Any, List, Optional, Generator
from config import OLLAMA_BASE_URL, OLLAMA_NUM_CTX, OLLAMA_KEEP_ALIVE, OLLAMA_TIMEOUT, detect_ollama_endpoint
from core.logger import setup_logger

logger = setup_logger("LLMAgent")

SYSTEM_INTENT_PROMPT = """You are StorageSense Intent Parser.
Convert user commands into a structured JSON object.

Allowed actions:
- "SEARCH": Find files, documents, or images by topic or keyword.
- "DELETE": Request to remove files or clean up storage.
- "ARCHIVE": Request to organize or compress files.
- "DEDUPLICATE": Request to find and clean duplicate versions.
- "FREE_SPACE": Request to free up storage space (e.g., "free up 5 GB").

Output ONLY a single raw JSON object matching this schema:
{
  "action": "SEARCH" | "DELETE" | "ARCHIVE" | "DEDUPLICATE" | "FREE_SPACE",
  "query": "core search terms or topic",
  "file_types": ["pdf", "docx"] or null,
  "target_gb": 5.0 or null,
  "keep_latest": true or false
}
"""

class LLMAgent:
    _instance = None

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super(LLMAgent, cls).__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(
        self,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        num_ctx: int = OLLAMA_NUM_CTX,
        keep_alive: str = OLLAMA_KEEP_ALIVE
    ):
        if getattr(self, "_initialized", False):
            return

        self.base_url = (base_url or detect_ollama_endpoint()).rstrip("/")
        self.num_ctx = num_ctx
        self.keep_alive = keep_alive
        self.model = model or "auto"
        
        # Auto-detect available models and choose the best one
        self.auto_detect_model()
        self._initialized = True

    def get_available_models(self, force_refresh: bool = False) -> List[str]:
        """Queries local Ollama daemon for installed models with short cache."""
        now = time.time()
        if not force_refresh and hasattr(self, "_cached_models") and (now - getattr(self, "_models_cached_at", 0) < 10.0):
            return self._cached_models

        models = []
        try:
            r = requests.get(f"{self.base_url}/api/tags", timeout=3.0)
            if r.status_code == 200:
                data = r.json()
                models = [m["name"] for m in data.get("models", [])]
        except Exception as e_tags:
            logger.debug(f"Could not reach Ollama at {self.base_url}/api/tags: {e_tags}")

        if models:
            self._cached_models = models
            self._models_cached_at = now
        elif hasattr(self, "_cached_models") and self._cached_models:
            # Preserve existing model cache if transient timeout occurs during heavy disk I/O
            return self._cached_models
        else:
            self._cached_models = []
            self._models_cached_at = now
        return self._cached_models

    def auto_detect_model(self) -> str:
        """Dynamically detects and selects the best local Ollama model."""
        models = self.get_available_models()
        if not models:
            self.model = "none"
            return "none"

        # If current model is valid and in list, keep it
        if self.model != "auto" and self.model != "none" and self.model in models:
            return self.model

        # Check for currently running model in memory first
        try:
            r_ps = requests.get(f"{self.base_url}/api/ps", timeout=1.5)
            if r_ps.status_code == 200:
                ps_models = [m["name"] for m in r_ps.json().get("models", [])]
                if ps_models:
                    self.model = ps_models[0]
                    logger.info(f"Auto-detected active in-memory model: {self.model}")
                    return self.model
        except Exception as e_ps:
            logger.debug(f"Could not reach Ollama /api/ps: {e_ps}")

        # Prioritize optimal mobile/edge instruct models (filter out non-generative embedding models)
        candidate_models = [
            m for m in models 
            if not any(bad in m.lower() for bad in ["embed", "bge", "clip", "nomic-embed", "paraphrase"])
        ]
        active_pool = candidate_models if candidate_models else models

        priority_keywords = ["gemma4", "gemma", "qwen", "llama", "mistral", "phi"]
        for kw in priority_keywords:
            for m in active_pool:
                if kw in m.lower():
                    self.model = m
                    logger.info(f"Auto-detected optimal model: {self.model}")
                    return self.model

        # Fallback to the first available non-embedding model
        self.model = active_pool[0]
        logger.info(f"Auto-selected default installed model: {self.model}")
        return self.model

    def set_active_model(self, model_name: str) -> bool:
        """Allows interactive model switching from the UI or CLI."""
        models = self.get_available_models()
        if model_name in models or model_name == "none":
            self.model = model_name
            logger.info(f"Switched active Ollama model to: {self.model}")
            return True
        return False

    def is_available(self) -> bool:
        return bool(self.get_available_models())

    def get_model_telemetry(self, force_refresh: bool = False) -> Dict[str, Any]:
        """Queries local Ollama daemon for active models and latency with short cache."""
        now = time.time()
        if not force_refresh and hasattr(self, "_cached_telemetry") and (now - getattr(self, "_telemetry_cached_at", 0) < 10.0):
            return self._cached_telemetry

        t0 = time.perf_counter()
        models = self.get_available_models(force_refresh=force_refresh)
        latency_ms = round((time.perf_counter() - t0) * 1000, 1)

        if models:
            # Refresh active model if it became invalid
            if self.model == "none" or self.model not in models:
                self.auto_detect_model()

            res = {
                "status": "online",
                "latency_ms": latency_ms,
                "active_model": self.model,
                "available_models": models,
                "num_ctx": self.num_ctx
            }
        else:
            res = {
                "status": "offline",
                "latency_ms": 0.0,
                "active_model": "None (Offline)",
                "available_models": [],
                "num_ctx": self.num_ctx
            }

        self._cached_telemetry = res
        self._telemetry_cached_at = now
        return res

    def parse_intent(self, user_prompt: str) -> Dict[str, Any]:
        t0 = time.perf_counter()
        lower_p = user_prompt.lower().strip()

        # Extract potential target storage size
        target_gb = None
        gb_match = re.search(r'(\d+(?:\.\d+)?)\s*(?:gb|gigabytes?|gigs?)', lower_p)
        if gb_match:
            try:
                target_gb = float(gb_match.group(1))
            except ValueError:
                pass
        else:
            mb_match = re.search(r'(\d+(?:\.\d+)?)\s*(?:mb|megabytes?)', lower_p)
            if mb_match:
                try:
                    target_gb = round(float(mb_match.group(1)) / 1024.0, 3)
                except ValueError:
                    pass

        # Extract file types if explicitly specified
        file_types = None
        type_matches = re.findall(r'\b(pdf|docx?|pptx?|txt|md|python|py|images?|photos?|pics?)\b', lower_p)
        if type_matches:
            ext_map = {
                "pdf": [".pdf"],
                "doc": [".docx"], "docx": [".docx"],
                "ppt": [".pptx"], "pptx": [".pptx"],
                "txt": [".txt"], "md": [".md"],
                "python": [".py"], "py": [".py"],
                "image": [".jpg", ".png", ".webp"], "images": [".jpg", ".png", ".webp"],
                "photo": [".jpg", ".png"], "photos": [".jpg", ".png"],
                "pic": [".jpg", ".png"], "pics": [".jpg", ".png"]
            }
            clean_exts = set()
            for tm in type_matches:
                if tm in ext_map:
                    clean_exts.update(ext_map[tm])
            if clean_exts:
                file_types = list(clean_exts)

        # Extract target drive if explicitly specified
        target_drive = None
        drive_m = re.search(r'(?:\b(?:in|on|from)\s+)?\b([A-Za-z])\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', user_prompt, flags=re.I)
        if drive_m:
            letter = drive_m.group(1).upper()
            target_drive = f"{letter}:\\"
        elif re.search(r'\b([A-Za-z]):(?:\\|/)?', user_prompt):
            m2 = re.search(r'\b([A-Za-z]):(?:\\|/)?', user_prompt)
            target_drive = f"{m2.group(1).upper()}:\\"

        # Word-boundary based heuristic classifier (<1ms, no freezing)
        if re.search(r'\b(duplicate|duplicates|dedup|find\s+clones?|cloned\s+files?)\b', lower_p):
            action = "DEDUPLICATE"
            query = user_prompt
        elif re.search(r'\b(delete\s+all|permanently\s+remove|purge\s+trash|empty\s+trash)\b', lower_p):
            action = "DELETE"
            query = re.sub(r'\b(delete\s+all|permanently\s+remove|purge\s+trash|empty\s+trash)\b', '', lower_p).strip() or user_prompt
        elif re.search(r'\b(free\s+up|reclaim|save\s+space|clean\s+up|clean)\b', lower_p):
            action = "FREE_SPACE"
            query = re.sub(r'\b(free\s+up|reclaim|save\s+space|clean\s+up|clean)\b', '', lower_p).strip() or user_prompt
        else:
            action = "SEARCH"
            # Strip natural conversational prefixes
            clean_q = re.sub(
                r'^(i\s+(ne+d|want)\s+(you\s+)?to\s+(find(\s+out)?(\s+where)?|locate|search(\s+for)?)|can\s+you\s+(please\s+)?(find(\s+out)?(\s+where)?|locate|search|show(\s+me)?)|please\s+(find|locate|search|show\s+me)|find\s+all|find(\s+out\s+where)?|search\s+for|search|show\s+me\s+all|show\s+me|where\s+is|where\s+are|where|look\s+for|get)\s+',
                '', lower_p
            ).strip()
            # Strip trailing conversational clauses
            clean_q = re.sub(r',?\s*i\s+don\'?t\s+know.*$', '', clean_q).strip()
            clean_q = re.sub(r'\s+(is|are)\s+(stored|kept|located|saved).*$', '', clean_q).strip()
            clean_q = re.sub(r'^(the|all|my|any)\s+', '', clean_q).strip()
            # Strip drive indicators from query
            clean_q = re.sub(r'\b(?:in|on|from)\s+[a-z]\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', ' ', clean_q, flags=re.I)
            clean_q = re.sub(r'\b[a-z]\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', ' ', clean_q, flags=re.I)
            clean_q = re.sub(r'\b[a-z]:[\\/]?', ' ', clean_q)
            clean_q = clean_q.strip()
            # Strip conversational prefixes again if drive prefix was at the start
            clean_q = re.sub(
                r'^(i\s+(ne+d|want)\s+(you\s+)?to\s+(find(\s+out)?(\s+where)?|locate|search(\s+for)?)|can\s+you\s+(please\s+)?(find(\s+out)?(\s+where)?|locate|search|show(\s+me)?)|please\s+(find|locate|search|show\s+me)|find\s+all|find(\s+out\s+where)?|search\s+for|search|show\s+me\s+all|show\s+me|where\s+is|where\s+are|where|look\s+for|get)\s+',
                '', clean_q
            ).strip()
            clean_q = re.sub(r'\s+', ' ', clean_q).strip()
            query = clean_q or user_prompt

        elapsed = round((time.perf_counter() - t0) * 1000, 2)
        return {
            "action": action,
            "query": query,
            "file_types": file_types,
            "target_drive": target_drive,
            "target_gb": target_gb,
            "keep_latest": True,
            "parse_time_ms": elapsed,
            "method": "instant_classifier"
        }

    def _extract_rich_context(self, f: Dict[str, Any], max_chars: int = 1200) -> str:
        """Safely extracts rich snippet/text content from chunks or directly from file."""
        chunks = f.get("matching_chunks")
        if chunks and isinstance(chunks, list):
            valid_chunks = [str(c).strip() for c in chunks if c and len(str(c).strip()) > 15]
            if valid_chunks:
                return " ... ".join(valid_chunks)[:max_chars]

        snip = f.get("best_snippet", "")
        if snip and len(str(snip).strip()) > 25:
            return str(snip).strip()[:max_chars]

        fpath_str = f.get("file_path", "")
        if fpath_str:
            try:
                p = Path(fpath_str)
                if p.exists() and p.is_file():
                    ext = p.suffix.lower()
                    if ext in {".txt", ".md", ".py", ".json", ".csv", ".log", ".java", ".c", ".cpp", ".html", ".js"}:
                        with open(p, "r", encoding="utf-8", errors="ignore") as fp:
                            txt = fp.read(max_chars)
                            if txt.strip():
                                return txt.strip()
                    elif ext == ".pdf":
                        try:
                            import pymupdf as fitz
                        except ImportError:
                            import fitz
                        doc = fitz.open(p)
                        txt = ""
                        for page in doc[:3]:
                            txt += page.get_text() + " "
                            if len(txt) >= max_chars:
                                break
                        doc.close()
                        if txt.strip():
                            return txt.strip()[:max_chars]
            except Exception:
                pass

        return str(snip) if snip else f"Relevant document: {f.get('file_name', '')}"

    def _generate_extractive_summary(self, query: str, matched_files: List[Dict[str, Any]]) -> str:
        """
        Synthesizes an intelligent, structured extractive AI summary across matched files
        when local Ollama is offline, cold-starting, or encounters a timeout.
        """
        if not matched_files:
            return "No matching files were found in your storage."

        total = len(matched_files)
        clean_q = query.strip()
        lines = []
        lines.append(f"**Grounded Summary for '{clean_q}'** ({total} matched document{'s' if total > 1 else ''}):\n")

        for idx, f in enumerate(matched_files[:4], 1):
            fname = f.get("file_name", "Document")
            fpath = f.get("file_path", "")
            context = self._extract_rich_context(f, max_chars=350)
            cleaned = re.sub(r'\s+', ' ', context).strip()
            if len(cleaned) > 220:
                cleaned = cleaned[:217] + "..."

            verified = f.get("is_verified_identity")
            badge = f" [Verified {verified.upper()}]" if verified else ""
            lines.append(f"{idx}. **{fname}**{badge}:")
            lines.append(f"   \"{cleaned}\"")
            lines.append(f"   *(Location: `{fpath}`)*\n")

        return "\n".join(lines)

    def _stream_text_chunks(self, text: str) -> Generator[str, None, None]:
        """Streams pre-computed text in token-like chunks for responsive UI display."""
        words = text.split(" ")
        buf = []
        for w in words:
            buf.append(w)
            if len(buf) >= 3 or "\n" in w:
                yield " ".join(buf) + " "
                buf = []
        if buf:
            yield " ".join(buf)

    def generate_rag_stream(self, query: str, matched_files: List[Dict[str, Any]]) -> Generator[str, None, None]:
        """Streams tokens in real-time using Ollama SSE streaming (<300ms Time-to-First-Token)."""
        if not matched_files:
            yield "No matching files were found in your indexed storage."
            return

        # Dynamically refresh model detection if needed
        if not self.is_available() or self.model == "none":
            self.auto_detect_model()

        if not self.is_available() or self.model == "none":
            summary = self._generate_extractive_summary(query, matched_files)
            for chunk in self._stream_text_chunks(summary):
                yield chunk
            return

        context_parts = []
        for idx, f in enumerate(matched_files[:5]):
            content = self._extract_rich_context(f, max_chars=1200)
            context_parts.append(f"[{idx+1}] File: {f['file_name']}\nPath: {f['file_path']}\nContent: {content}")

        context_str = "\n\n".join(context_parts)
        prompt = (
            f"You are StorageSense, an intelligent local personal storage assistant. "
            f"Synthesize a clear, accurate, and helpful AI summary answering the user's query based on the matched file excerpts below.\n\n"
            f"Context:\n{context_str}\n\n"
            f"User Query: {query}\n\n"
            f"Instructions:\n"
            f"1. Directly answer the user's inquiry based on what is found in the documents.\n"
            f"2. Summarize key facts, topics, or numbers from the most relevant files (mentioning the file names).\n"
            f"3. Keep the summary structured and concise."
        )

        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": True,
            "think": False,
            "options": {
                "num_ctx": self.num_ctx,
                "temperature": 0.2,
                "num_predict": 350
            },
            "keep_alive": self.keep_alive
        }

        yielded_any = False
        read_timeout = max(60.0, float(OLLAMA_TIMEOUT))
        try:
            with requests.post(f"{self.base_url}/api/generate", json=payload, stream=True, timeout=(15.0, read_timeout)) as response:
                if response.status_code == 200:
                    for line in response.iter_lines():
                        if line:
                            chunk = json.loads(line.decode("utf-8"))
                            token = chunk.get("response", "")
                            if not token and chunk.get("thinking"):
                                token = chunk.get("thinking", "")
                            if token:
                                yielded_any = True
                                yield token
                            if chunk.get("done", False):
                                break
                    if yielded_any:
                        return
        except Exception as e:
            logger.info(f"Ollama generation deferred ({e}). Generating grounded extractive summary.")
            if yielded_any:
                yield "\n\n[Response generation interrupted]"
                return

        # Fallback to intelligent extractive summary if streaming encounters an error or timeout
        summary = self._generate_extractive_summary(query, matched_files)
        for chunk in self._stream_text_chunks(summary):
            yield chunk

    def generate_rag_answer(self, query: str, matched_files: List[Dict[str, Any]]) -> str:
        tokens = list(self.generate_rag_stream(query, matched_files))
        return "".join(tokens)
