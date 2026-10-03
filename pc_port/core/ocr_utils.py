import asyncio
from pathlib import Path
from typing import Optional
from PIL import Image
from core.logger import setup_logger

logger = setup_logger("OcrUtils")

def extract_text_from_image(image_path: Path | str) -> str:
    """
    Extracts text from an image using winocr (on-device Windows Media OCR).
    Falls back to pytesseract if winocr encounters an error.
    """
    path = Path(image_path)
    if not path.exists():
        return ""

    # Attempt 1: Native on-device Windows Media OCR (Zero-cloud, hardware-accelerated)
    try:
        import winocr
        async def _run():
            with Image.open(str(path)) as img:
                res = await winocr.recognize_pil(img)
                return res.text.strip()
        text = asyncio.run(_run())
        if text:
            return text
    except Exception as e:
        logger.debug(f"winocr recognition skipped/failed for {path.name}: {e}")

    # Attempt 2: Pytesseract fallback if installed
    try:
        import pytesseract
        with Image.open(str(path)) as img:
            text = pytesseract.image_to_string(img).strip()
            if text:
                return text
    except Exception as e_pyt:
        logger.debug(f"pytesseract fallback skipped/failed for {path.name}: {e_pyt}")

    return ""

def ocr_scanned_pdf_pages(pdf_path: Path | str, max_pages: int = 3) -> str:
    """
    Renders pixmaps of scanned PDF pages and extracts text using winocr.
    """
    path = Path(pdf_path)
    if not path.exists():
        return ""

    try:
        import fitz
        import winocr
        async def _ocr(pil_img):
            res = await winocr.recognize_pil(pil_img)
            return res.text.strip()

        texts = []
        with fitz.open(str(path)) as doc:
            for pno in range(min(max_pages, len(doc))):
                page = doc[pno]
                # If page already has digital text, use it
                t = page.get_text().strip()
                if t:
                    texts.append(t)
                else:
                    # Scanned page - render at 150 DPI and OCR
                    pix = page.get_pixmap(dpi=150)
                    img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
                    ocr_t = asyncio.run(_ocr(img))
                    if ocr_t:
                        texts.append(ocr_t)
        return "\n".join(texts).strip()
    except Exception as e:
        logger.debug(f"PDF OCR failed for {path.name}: {e}")
        return ""
