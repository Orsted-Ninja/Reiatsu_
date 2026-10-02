import os
import re
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple

from config import SUPPORTED_EXTENSIONS, TEXT_EXTENSIONS
from core.database import Database
from parsers.extractor_factory import ExtractorFactory
from core.system_ops import get_standard_user_folders, get_available_drives
from core.logger import setup_logger
from core.query_parser import QueryRoleParser, QueryRoles
from core.document_ontology import DocumentOntology, OntologyEntity

logger = setup_logger("LiveScanner")

class LiveFilesystemScanner:
    """
    Tier-2 Just-In-Time Filesystem Discovery Scanner.
    Finds unchunked, unindexed, or obscurely named files across the computer live,
    inspecting filenames and raw file content in real time with zero prior indexing required.
    """
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
        "now", "neeed", "need", "find", "stored", "know", "please", "tell", "show", "give"
    }

    IGNORE_DIRS = {
        ".trash", ".cache", "__pycache__", ".git", ".venv", "venv", "env",
        "node_modules", "appdata", "site-packages", "dist-packages", ".idea", ".vscode",
        "windows", "program files", "program files (x86)", "programdata",
        "recovery", "$winreagent", "$recycle.bin", "recycle.bin",
        "system volume information", "msdownld.tmp", "config.msi", "intel", "perflogs",
        "dell", "drivers", "msys64", "msys", "mingw32", "mingw64", "ucrt64",
        "clang64", "clangarm64", "libstdc++", "nuget", "gradle", ".m2", "target", "build", "dist"
    }

    def __init__(self, db: Optional[Database] = None):
        self.db = db or Database()
        self.factory = ExtractorFactory()
        self._ignore_dirs_lower = {d.lower() for d in self.IGNORE_DIRS}
        self.last_scan_diagnostics: Dict[str, Any] = {}


    def detect_target_drive(self, query: str) -> Optional[str]:
        """Detects if query explicitly mentions a drive like 'D:', 'F drive', 'in D:\\'."""
        m = re.search(r'(?:\b(?:in|on|from)\s+)?\b([A-Za-z])\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', query, re.I)
        if m:
            letter = m.group(1).upper()
            return f"{letter}:\\"
        m2 = re.search(r'\b([A-Za-z]):(?:\\|/)?', query)
        if m2:
            return f"{m2.group(1).upper()}:\\"
        return None

    def _extract_query_terms(self, query: str) -> Tuple[List[str], List[str]]:
        """Extracts quoted phrases, multi-word excerpt shingles, and clean keywords via QueryRoleParser."""
        roles = QueryRoleParser.parse(query)
        phrases = list(roles.phrases)
        keywords = list(roles.all_keywords)

        entity = DocumentOntology.match_entity(query, roles)
        if entity:
            for syn in entity.anchor_synonyms:
                if syn not in keywords and syn not in phrases:
                    keywords.append(syn)
        else:
            from core.identity_expander import expand_identity_query
            expanded_list, _ = expand_identity_query(query)
            for term in expanded_list:
                if term not in keywords and term not in phrases:
                    keywords.append(term)

        return phrases, keywords

    def _get_indexed_paths(self) -> set:
        try:
            with self.db.get_connection() as conn:
                rows = conn.execute("SELECT file_path FROM file_registry").fetchall()
                return {r["file_path"].lower() for r in rows}
        except Exception:
            return set()

    def get_candidate_roots(
        self,
        custom_dirs: Optional[List[str | Path]] = None,
        target_drive: Optional[str] = None
    ) -> List[Path]:
        if custom_dirs:
            return [Path(d).resolve() for d in custom_dirs if Path(d).exists()]

        avail_drives = get_available_drives()

        if target_drive:
            clean_d = target_drive.strip().upper()
            if not clean_d.endswith("\\"):
                clean_d = f"{clean_d}:\\" if not clean_d.endswith(":") else f"{clean_d}\\"

            matched_drive_path = None
            for d_name, d_path in avail_drives.items():
                if d_path.upper().startswith(clean_d[:2]) or d_name.upper().startswith(f"DRIVE {clean_d[:2]}"):
                    matched_drive_path = Path(d_path)
                    break
            if not matched_drive_path and Path(clean_d).exists():
                matched_drive_path = Path(clean_d)

            if matched_drive_path and matched_drive_path.exists():
                roots = []
                user_dirs = get_standard_user_folders()
                for p_str in user_dirs.values():
                    p = Path(p_str)
                    if p.exists() and str(p).upper().startswith(clean_d[:2]) and p not in roots:
                        roots.append(p)
                if matched_drive_path not in roots:
                    roots.append(matched_drive_path)
                return roots

        roots = []
        user_dirs = get_standard_user_folders()
        for path_str in user_dirs.values():
            p = Path(path_str)
            if p.exists() and p not in roots:
                roots.append(p)

        for drive_path in avail_drives.values():
            dp = Path(drive_path)
            if dp.exists() and dp not in roots:
                roots.append(dp)

        return roots

    def _matches_filename(self, fname: str, keywords: List[str], phrases: List[str]) -> List[str]:
        """Strict word-boundary matching on filenames to avoid substring false positives (e.g. discard vs card)."""
        fname_lower = fname.lower()
        norm_name = re.sub(r'[-_.]', ' ', fname_lower)
        hits = []
        for p in phrases:
            if p in fname_lower or p in norm_name:
                hits.append(p)
        for k in keywords:
            if " " in k:
                if k in fname_lower or k in norm_name:
                    hits.append(k)
            else:
                if re.search(rf'\b{re.escape(k)}\b', norm_name):
                    hits.append(k)
        return hits

    def _inspect_file_content(
        self,
        file_path: Path,
        keywords: List[str],
        phrases: List[str],
        identity_constraint: Optional[Dict[str, Any]] = None,
        roles: Optional[QueryRoles] = None,
        ontology_entity: Optional[OntologyEntity] = None
    ) -> Optional[Tuple[float, str]]:
        """
        Fast on-the-fly content inspection of unindexed files.
        Directly checks text, PDF, and Office docs without heavy hashing or full chunking.
        Uses strict word boundaries for ALL terms to eliminate false positive substring matches.
        Enforces Universal Anchor Gating and Document Ontology validation.
        """
        ext = file_path.suffix.lower()
        text = ""

        try:
            if ext in TEXT_EXTENSIONS:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    text = f.read(512 * 1024)
            elif ext == ".pdf":
                import fitz
                with fitz.open(str(file_path)) as doc:
                    if doc.is_encrypted:
                        return None
                    max_pages = min(15, len(doc))
                    page_texts = [doc[p_idx].get_text() or "" for p_idx in range(max_pages)]
                    text = "\n".join(page_texts)
                    if len(text.strip()) < 30:
                        from core.ocr_utils import ocr_scanned_pdf_pages
                        text = ocr_scanned_pdf_pages(file_path, max_pages=2)
            elif ext in {".docx", ".pptx"}:
                doc = self.factory.parse_file(file_path)
                if doc and doc.full_text:
                    text = doc.full_text[:512 * 1024]
            elif ext in {".png", ".jpg", ".jpeg", ".webp"}:
                from core.ocr_utils import extract_text_from_image
                text = extract_text_from_image(file_path)
        except Exception:
            return None

        if not text:
            return None

        text_lower = text.lower()

        # 1. Document Ontology Validation (PAN, Aadhaar, Passport, Bank Stmt, Electricity Bill, etc.)
        if ontology_entity:
            is_qual, is_ver, snip = DocumentOntology.validate_candidate(text, file_path.name, ontology_entity)
            if is_qual:
                score = 0.120 if is_ver else 0.095
                return score, snip
            else:
                return None

        # 2. Legacy Identity Constraint check (backward compatibility)
        if identity_constraint:
            regex = identity_constraint.get("regex")
            req_terms = identity_constraint["required_terms"]

            if regex:
                m_regex = regex.search(text)
                if m_regex:
                    match_val = m_regex.group(0)
                    pos = text.find(match_val)
                    start = max(0, pos - 60)
                    end = min(len(text), pos + 120)
                    raw_snip = text[start:end].replace("\n", " ").strip()
                    id_type = identity_constraint["identity_type"]
                    return 0.120, f"[VERIFIED {id_type.upper()}] ...{raw_snip}..."

            has_req_hit = False
            first_req = None
            for rt in req_terms:
                if " " in rt:
                    if rt in text_lower:
                        has_req_hit = True
                        first_req = rt
                        break
                else:
                    if re.search(rf'\b{re.escape(rt)}\b', text_lower):
                        has_req_hit = True
                        first_req = rt
                        break

            if not has_req_hit:
                return None

            pos = text_lower.find(first_req)
            start = max(0, pos - 60)
            end = min(len(text), pos + 120)
            raw_snip = text[start:end].replace("\n", " ").strip()
            return 0.095, f"[{identity_constraint['identity_type'].upper()}] ...{raw_snip}..."

        # 3. Universal Anchor Gating: if query has core anchors, the file MUST match >= 1 anchor!
        # Container terms (e.g. card, bill, manual, report) alone CANNOT qualify a file.
        if roles and roles.has_anchors:
            fn_lower = file_path.name.lower()
            norm_fn = re.sub(r'[-_.]', ' ', fn_lower)
            has_anchor_hit = False
            for a in roles.anchors:
                if " " in a:
                    if a in text_lower or a in fn_lower or a in norm_fn:
                        has_anchor_hit = True
                        break
                else:
                    if re.search(rf'\b{re.escape(a)}\b', text_lower) or re.search(rf'\b{re.escape(a)}\b', norm_fn):
                        has_anchor_hit = True
                        break
            if not has_anchor_hit:
                # Crucial: Disqualify file completely! Does not contain required domain anchors!
                return None

        content_hits = []

        # Check quoted phrases or multi-word continuous shingles first
        for ph in phrases:
            if ph in text_lower:
                content_hits.append(ph)
                if len(ph.split()) >= 3:
                    # Direct multi-word passage/shingle match from user prompt
                    pos = text_lower.find(ph)
                    start = max(0, pos - 60)
                    end = min(len(text), pos + len(ph) + 120)
                    raw_snip = text[start:end].replace("\n", " ").strip()
                    score = 0.088 + min(0.010, 0.001 * len(ph))
                    return score, f"...{raw_snip}..."

        # Check keywords with strict word boundaries for ALL keywords
        # Eliminates false positives like "card" matching "discard" or "backward", "pan" matching "japan"
        for k in keywords:
            if " " in k:
                if k in text_lower:
                    content_hits.append(k)
            else:
                if re.search(rf'\b{re.escape(k)}\b', text_lower):
                    content_hits.append(k)

        # Check for Aadhaar 12-digit number pattern if identity search
        is_aadhaar_query = any(k in ["aadhar", "aadhaar", "adhar", "uidai"] for k in keywords + phrases)
        if is_aadhaar_query:
            from core.identity_expander import AADHAAR_REGEX
            m_uid = AADHAAR_REGEX.search(text)
            if m_uid:
                content_hits.append(m_uid.group(0))
                pos = text.find(m_uid.group(0))
                start = max(0, pos - 60)
                end = min(len(text), pos + 120)
                raw_snip = text[start:end].replace("\n", " ").strip()
                return 0.098, f"[VERIFIED AADHAAR UID] ...{raw_snip}..."

        # Check for PAN 10-char alphanumeric pattern if identity search
        is_pan_query = any(k in ["pan", "utiitsl", "nsdl"] or "permanent account" in k for k in keywords + phrases)
        if is_pan_query:
            from core.identity_expander import PAN_REGEX
            m_pan = PAN_REGEX.search(text)
            if m_pan:
                content_hits.append(m_pan.group(0))
                pos = text.find(m_pan.group(0))
                start = max(0, pos - 60)
                end = min(len(text), pos + 120)
                raw_snip = text[start:end].replace("\n", " ").strip()
                return 0.098, f"[VERIFIED PAN] ...{raw_snip}..."

        if not content_hits:
            return None

        total_terms = max(1, len(keywords) + len(phrases))
        ratio = len(content_hits) / total_terms
        has_specific_kw = any(any(c.isdigit() for c in k) or len(k) >= 6 for k in content_hits)

        # Calibrated RRF scale (0.040 - 0.085)
        if ratio == 1.0:
            score = 0.082
        elif has_specific_kw:
            score = 0.062 + (0.020 * ratio)
        else:
            score = 0.042 + (0.020 * ratio)

        first_kw = content_hits[0]
        pos = text_lower.find(first_kw)
        start = max(0, pos - 60)
        end = min(len(text), pos + 120)
        raw_snip = text[start:end].replace("\n", " ").strip()
        snippet = f"...{raw_snip}..."

        return score, snippet


    def scan_live(
        self,
        query: str,
        target_dirs: Optional[List[str | Path]] = None,
        target_drive: Optional[str] = None,
        file_types: Optional[List[str]] = None,
        max_matches: int = 10,
        timeout_seconds: float = 8.0,
        auto_chunk: bool = False,
        auto_index_callback: Optional[Any] = None
    ) -> List[Dict[str, Any]]:
        """
        Executes an exhaustive, real-time filesystem sweep across candidate directories.
        Samples unindexed documents for content matches and fuzzy keyword relevance.
        Guarantees deep recursive discovery without premature abortion from shallow weak matches.
        """
        t_start = time.perf_counter()
        if not target_drive:
            target_drive = self.detect_target_drive(query)

        phrases, keywords = self._extract_query_terms(query)
        if not keywords and not phrases:
            return []

        clean_types = None
        if file_types:
            clean_types = {t.lower() if t.startswith(".") else f".{t.lower()}" for t in file_types}

        if target_drive or target_dirs:
            timeout_seconds = max(timeout_seconds, 12.0)

        indexed_paths = self._get_indexed_paths()
        candidate_roots = self.get_candidate_roots(custom_dirs=target_dirs, target_drive=target_drive)
        matches: List[Dict[str, Any]] = []
        visited_dirs = set()
        files_examined_count = 0

        drive_info = f" (Target Drive: {target_drive})" if target_drive else ""
        roles = QueryRoleParser.parse(query)
        ontology_entity = DocumentOntology.match_entity(query, roles)
        from core.identity_expander import get_identity_constraints
        identity_constraint = get_identity_constraints(query)
        is_personal_id_query = bool(ontology_entity or identity_constraint)
        logger.info(f"Starting Live Deep Search for '{query}'{drive_info} (Anchors: {roles.anchors}, Containers: {roles.containers}) across {len(candidate_roots)} roots...")

        # Stage 1: Fast shallow pass across candidate root folders (capped at 35% time budget)
        t_stage1_limit = t_start + (timeout_seconds * 0.35)
        for root in candidate_roots:
            if time.perf_counter() > t_stage1_limit:
                break
            try:
                for entry in os.scandir(str(root)):
                    if time.perf_counter() > t_stage1_limit:
                        break
                    if entry.is_file(follow_symlinks=False):
                        files_examined_count += 1
                        fname = entry.name
                        if fname.startswith("~$") or fname.startswith("."):
                            continue
                        ext = os.path.splitext(fname)[1].lower()
                        if ext not in SUPPORTED_EXTENSIONS:
                            continue
                        if clean_types and ext not in clean_types:
                            continue
                        if is_personal_id_query and not clean_types and ext in {".py", ".java", ".c", ".cpp", ".h", ".cs", ".js", ".ts", ".html", ".css", ".json", ".log"}:
                            continue
                        full_path = entry.path
                        if full_path.lower() in indexed_paths or any(m["file_path"].lower() == full_path.lower() for m in matches):
                            continue

                        name_hits = self._matches_filename(fname, keywords, phrases)
                        is_match = False
                        matched_snippet = ""
                        score = 0.0

                        file_p = Path(full_path)
                        insp = None
                        try:
                            if file_p.stat().st_size <= 25 * 1024 * 1024:
                                insp = self._inspect_file_content(
                                    file_p, keywords, phrases,
                                    identity_constraint=identity_constraint,
                                    roles=roles,
                                    ontology_entity=ontology_entity
                                )
                        except Exception as e_insp:
                            logger.debug(f"Error inspecting file content {file_p}: {e_insp}")

                        if insp:
                            c_score, c_snippet = insp
                            score = c_score + (0.010 if name_hits else 0.0)
                            matched_snippet = c_snippet
                            is_match = True
                        elif name_hits:
                            name_qualifies = False
                            if ontology_entity:
                                is_qual, _, _ = DocumentOntology.validate_candidate("", fname, ontology_entity)
                                name_qualifies = is_qual
                            elif identity_constraint:
                                req_terms = identity_constraint["required_terms"]
                                fname_lower = fname.lower()
                                norm_fn = re.sub(r'[-_.]', ' ', fname_lower)
                                name_qualifies = any(
                                    (rt in fname_lower) if " " in rt else bool(re.search(rf'\b{re.escape(rt)}\b', norm_fn))
                                    for rt in req_terms
                                )
                            elif roles and roles.has_anchors:
                                fname_lower = fname.lower()
                                norm_fn = re.sub(r'[-_.]', ' ', fname_lower)
                                name_qualifies = any(
                                    (a in fname_lower) if " " in a else bool(re.search(rf'\b{re.escape(a)}\b', norm_fn))
                                    for a in roles.anchors
                                )
                            else:
                                name_qualifies = bool(name_hits)

                            if name_qualifies:
                                total_terms = max(1, len(keywords) + len(phrases))
                                ratio = len(name_hits) / total_terms
                                score = 0.050 + (0.035 * ratio)
                                matched_snippet = f"[Filename Match ({len(name_hits)}/{total_terms} keywords)] {fname}"
                                is_match = True

                        if is_match:
                            match_item = {
                                "file_path": full_path,
                                "file_name": fname,
                                "fused_score": round(score, 4),
                                "best_snippet": matched_snippet,
                                "matching_chunks": [matched_snippet],
                                "is_live_discovery": True,
                                "is_chunked": False,
                                "chunk_count": 0
                            }
                            matches.append(match_item)
                            if auto_chunk and auto_index_callback:
                                try:
                                    if not hasattr(self, "_bg_indexer_pool"):
                                        self._bg_indexer_pool = ThreadPoolExecutor(max_workers=1)
                                    self._bg_indexer_pool.submit(auto_index_callback, Path(full_path))
                                except Exception as e_sub:
                                    logger.debug(f"Could not submit auto-index for {full_path}: {e_sub}")
            except Exception as e_dir:
                logger.debug(f"Error scanning candidate folder {d}: {e_dir}")

        # Stage 2: Deep recursive traversal into user folders and mounted roots
        for root in candidate_roots:
            if time.perf_counter() - t_start > timeout_seconds:
                break

            for dirpath, dirnames, filenames in os.walk(str(root)):
                if time.perf_counter() - t_start > timeout_seconds:
                    break

                norm_dir = os.path.normpath(dirpath).lower()
                if norm_dir in visited_dirs:
                    dirnames[:] = []
                    continue
                visited_dirs.add(norm_dir)

                # Prune system/cache/toolchain dirs in-place
                dirnames[:] = [
                    d for d in dirnames 
                    if d.lower() not in self._ignore_dirs_lower and not d.startswith(".")
                ]

                for fname in filenames:
                    if time.perf_counter() - t_start > timeout_seconds:
                        break

                    files_examined_count += 1
                    if fname.startswith("~$") or fname.startswith("."):
                        continue

                    ext = os.path.splitext(fname)[1].lower()
                    if ext not in SUPPORTED_EXTENSIONS:
                        continue
                    if clean_types and ext not in clean_types:
                        continue
                    if is_personal_id_query and not clean_types and ext in {".py", ".java", ".c", ".cpp", ".h", ".cs", ".js", ".ts", ".html", ".css", ".json", ".log"}:
                        continue

                    full_path = str(Path(dirpath) / fname)
                    if full_path.lower() in indexed_paths or any(m["file_path"].lower() == full_path.lower() for m in matches):
                        continue

                    name_hits = self._matches_filename(fname, keywords, phrases)
                    is_match = False
                    matched_snippet = ""
                    score = 0.0

                    file_p = Path(full_path)
                    insp = None
                    try:
                        if file_p.stat().st_size <= 25 * 1024 * 1024:
                            insp = self._inspect_file_content(
                                file_p, keywords, phrases,
                                identity_constraint=identity_constraint,
                                roles=roles,
                                ontology_entity=ontology_entity
                            )
                    except Exception as e_insp2:
                        logger.debug(f"Error inspecting file content {file_p}: {e_insp2}")

                    if insp:
                        c_score, c_snippet = insp
                        score = c_score + (0.010 if name_hits else 0.0)
                        matched_snippet = c_snippet
                        is_match = True
                    elif name_hits:
                        name_qualifies = False
                        if ontology_entity:
                            is_qual, _, _ = DocumentOntology.validate_candidate("", fname, ontology_entity)
                            name_qualifies = is_qual
                        elif identity_constraint:
                            req_terms = identity_constraint["required_terms"]
                            fname_lower = fname.lower()
                            norm_fn = re.sub(r'[-_.]', ' ', fname_lower)
                            name_qualifies = any(
                                (rt in fname_lower) if " " in rt else bool(re.search(rf'\b{re.escape(rt)}\b', norm_fn))
                                for rt in req_terms
                            )
                        elif roles and roles.has_anchors:
                            fname_lower = fname.lower()
                            norm_fn = re.sub(r'[-_.]', ' ', fname_lower)
                            name_qualifies = any(
                                (a in fname_lower) if " " in a else bool(re.search(rf'\b{re.escape(a)}\b', norm_fn))
                                for a in roles.anchors
                            )
                        else:
                            name_qualifies = bool(name_hits)

                        if name_qualifies:
                            total_terms = max(1, len(keywords) + len(phrases))
                            ratio = len(name_hits) / total_terms
                            score = 0.050 + (0.035 * ratio)
                            matched_snippet = f"[Filename Match ({len(name_hits)}/{total_terms} keywords)] {fname}"
                            is_match = True

                    if is_match:
                        match_item = {
                            "file_path": full_path,
                            "file_name": fname,
                            "fused_score": round(score, 4),
                            "best_snippet": matched_snippet,
                            "matching_chunks": [matched_snippet],
                            "is_live_discovery": True,
                            "is_chunked": False,
                            "chunk_count": 0
                        }
                        matches.append(match_item)

                        if auto_chunk and auto_index_callback:
                            try:
                                if not hasattr(self, "_bg_indexer_pool"):
                                    self._bg_indexer_pool = ThreadPoolExecutor(max_workers=1)
                                self._bg_indexer_pool.submit(auto_index_callback, Path(full_path))
                            except Exception as e_sub2:
                                logger.debug(f"Could not submit auto-index for {full_path}: {e_sub2}")

        matches.sort(key=lambda x: x["fused_score"], reverse=True)
        scan_duration_ms = round((time.perf_counter() - t_start) * 1000, 1)
        self.last_scan_diagnostics = {
            "roots_scanned": [str(r) for r in candidate_roots],
            "files_examined": files_examined_count,
            "scan_time_ms": scan_duration_ms,
            "matches_found": len(matches),
            "target_drive": target_drive
        }
        logger.info(f"Live Deep Search completed in {scan_duration_ms}ms: Examined {files_examined_count} files across {len(candidate_roots)} roots, found {len(matches)} matches.")
        return matches[:max_matches]
