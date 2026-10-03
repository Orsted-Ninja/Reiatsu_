import fitz
from pathlib import Path
from parsers.base import BaseParser, ParsedDocument, DocumentPage
from core.hash_utils import compute_file_hash
from core.logger import setup_logger

logger = setup_logger("PDFParser")

class PDFParser(BaseParser):
    def can_parse(self, file_path: Path) -> bool:
        return file_path.suffix.lower() == ".pdf"

    def parse(self, file_path: Path) -> ParsedDocument:
        path = Path(file_path)
        stat = path.stat()
        file_hash = compute_file_hash(path)

        pages = []
        full_text_parts = []
        total_chars = 0
        num_pages = 0
        is_scanned = False
        meta = {}

        try:
            with fitz.open(str(path)) as doc:
                if doc.is_encrypted:
                    logger.warning(f"Skipping encrypted/password-protected PDF: {path.name}")
                    return ParsedDocument(
                        file_path=str(path.resolve()),
                        file_name=path.name,
                        extension=path.suffix.lower(),
                        file_size=stat.st_size,
                        last_modified=stat.st_mtime,
                        content_hash=file_hash,
                        full_text="[Encrypted PDF Document - Password Protected]",
                        pages=[DocumentPage(page_number=1, text="[Encrypted Document]")],
                        is_scanned=False,
                        metadata={"encrypted": True}
                    )

                num_pages = len(doc)
                max_extract_pages = min(num_pages, 150)
                for idx in range(max_extract_pages):
                    page = doc[idx]
                    text = page.get_text() or ""
                    total_chars += len(text.strip())
                    pages.append(DocumentPage(page_number=idx + 1, text=text))
                    if text.strip():
                        full_text_parts.append(text)

                if num_pages > 150:
                    full_text_parts.append(f"\n[Note: Document truncated at 150 pages. Total pages: {num_pages}]")

                meta = doc.metadata or {}
                is_scanned = (max_extract_pages > 0 and (total_chars / max_extract_pages) < 30)

                if is_scanned:
                    from core.ocr_utils import ocr_scanned_pdf_pages
                    ocr_t = ocr_scanned_pdf_pages(path, max_pages=3)
                    if ocr_t:
                        full_text_parts.append(ocr_t)
                        if pages:
                            pages[0].text = ocr_t

        except Exception as e:
            logger.warning(f"Error reading PDF {path.name}: {e}")
            return ParsedDocument(
                file_path=str(path.resolve()),
                file_name=path.name,
                extension=path.suffix.lower(),
                file_size=stat.st_size,
                last_modified=stat.st_mtime,
                content_hash=file_hash,
                full_text=f"[Unreadable PDF: {str(e)}]",
                pages=[DocumentPage(page_number=1, text="[Unreadable Document]")],
                is_scanned=False,
                metadata={"error": str(e)}
            )

        full_text = "\n\n".join(full_text_parts)

        return ParsedDocument(
            file_path=str(path.resolve()),
            file_name=path.name,
            extension=path.suffix.lower(),
            file_size=stat.st_size,
            last_modified=stat.st_mtime,
            content_hash=file_hash,
            full_text=full_text,
            pages=pages,
            is_scanned=is_scanned,
            metadata={"num_pages": num_pages, "title": meta.get("title", ""), "author": meta.get("author", "")}
        )

    @staticmethod
    def render_thumbnail(file_path: Path, output_image_path: Path = None) -> bytes:
        try:
            with fitz.open(str(file_path)) as doc:
                if len(doc) == 0 or doc.is_encrypted:
                    return b""
                page = doc[0]
                pix = page.get_pixmap(dpi=100)
                png_bytes = pix.tobytes("png")
                
            if output_image_path:
                output_image_path.write_bytes(png_bytes)
            return png_bytes
        except Exception as e_thumb:
            logger.debug(f"PDF thumbnail rendering skipped/failed for {file_path}: {e_thumb}")
            return b""
