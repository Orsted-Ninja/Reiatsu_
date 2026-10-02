from pathlib import Path
from parsers.base import BaseParser, ParsedDocument, DocumentPage
from core.hash_utils import compute_file_hash
from config import TEXT_EXTENSIONS

class TextParser(BaseParser):
    def can_parse(self, file_path: Path) -> bool:
        return file_path.suffix.lower() in TEXT_EXTENSIONS

    def parse(self, file_path: Path) -> ParsedDocument:
        path = Path(file_path)
        stat = path.stat()
        file_hash = compute_file_hash(path)

        MAX_TEXT_BYTES = 25 * 1024 * 1024  # 25 MB ceiling
        text = ""

        if stat.st_size > MAX_TEXT_BYTES:
            # Stream first 10MB + last 5MB for giant logs/CSVs to prevent OOM
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    head = f.read(10 * 1024 * 1024)
                    f.seek(max(0, stat.st_size - (5 * 1024 * 1024)))
                    tail = f.read(5 * 1024 * 1024)
                text = f"{head}\n\n[... Truncated {round((stat.st_size - 15*1024*1024)/(1024*1024), 1)} MB to protect memory ...]\n\n{tail}"
            except Exception:
                text = f"[Large file {path.name} ({stat.st_size} bytes) could not be parsed safely]"
        else:
            for encoding in ["utf-8", "utf-16", "cp1252", "latin-1"]:
                try:
                    text = path.read_text(encoding=encoding, errors="replace")
                    break
                except Exception:
                    continue

        return ParsedDocument(
            file_path=str(path.resolve()),
            file_name=path.name,
            extension=path.suffix.lower(),
            file_size=stat.st_size,
            last_modified=stat.st_mtime,
            content_hash=file_hash,
            full_text=text,
            pages=[DocumentPage(page_number=1, text=text)],
            is_scanned=False,
            metadata={"char_count": len(text)}
        )
