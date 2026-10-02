from pathlib import Path
from PIL import Image
import imagehash
from parsers.base import BaseParser, ParsedDocument, DocumentPage
from core.hash_utils import compute_file_hash
from config import IMAGE_EXTENSIONS
from core.logger import setup_logger

logger = setup_logger("ImageParser")

class ImageParser(BaseParser):
    def can_parse(self, file_path: Path) -> bool:
        return file_path.suffix.lower() in IMAGE_EXTENSIONS

    def parse(self, file_path: Path) -> ParsedDocument:
        path = Path(file_path)
        stat = path.stat()
        file_hash = compute_file_hash(path)

        ocr_text = ""
        p_hash_str = ""
        dimensions = (0, 0)

        try:
            with Image.open(str(path)) as img:
                dimensions = img.size
                try:
                    p_hash = imagehash.phash(img)
                    p_hash_str = str(p_hash)
                except Exception:
                    p_hash_str = ""

            from core.ocr_utils import extract_text_from_image
            ocr_text = extract_text_from_image(path)
        except Exception as e:
            logger.warning(f"Error parsing image {path.name}: {e}")
            ocr_text = f"[Image read error: {e}]"

        header_info = f"[Image: {path.name}, Size: {dimensions[0]}x{dimensions[1]}]"
        full_text = f"{header_info}\n{ocr_text}".strip() if ocr_text else header_info

        return ParsedDocument(
            file_path=str(path.resolve()),
            file_name=path.name,
            extension=path.suffix.lower(),
            file_size=stat.st_size,
            last_modified=stat.st_mtime,
            content_hash=file_hash,
            full_text=full_text,
            pages=[DocumentPage(page_number=1, text=full_text)],
            image_hash=p_hash_str,
            is_scanned=True,
            metadata={"width": dimensions[0], "height": dimensions[1], "phash": p_hash_str}
        )
