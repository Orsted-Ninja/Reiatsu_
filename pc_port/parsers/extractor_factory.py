from pathlib import Path
from typing import Optional
from parsers.base import BaseParser, ParsedDocument
from parsers.pdf_parser import PDFParser
from parsers.docx_parser import DocxParser
from parsers.pptx_parser import PptxParser
from parsers.text_parser import TextParser
from parsers.image_parser import ImageParser
from core.logger import setup_logger

logger = setup_logger("ExtractorFactory")

class ExtractorFactory:
    def __init__(self):
        self.parsers: list[BaseParser] = [
            PDFParser(),
            DocxParser(),
            PptxParser(),
            TextParser(),
            ImageParser()
        ]

    def get_parser(self, file_path: Path) -> Optional[BaseParser]:
        path = Path(file_path)
        for parser in self.parsers:
            try:
                if parser.can_parse(path):
                    return parser
            except Exception as e:
                logger.warning(f"Error checking can_parse for {path.name}: {e}")
        return None

    def parse_file(self, file_path: Path) -> Optional[ParsedDocument]:
        parser = self.get_parser(file_path)
        if parser:
            try:
                return parser.parse(file_path)
            except Exception as e:
                logger.warning(f"Error parsing file {file_path.name}: {e}")
                return None
        return None
