from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional
from pathlib import Path

@dataclass
class DocumentPage:
    page_number: int
    text: str
    metadata: Dict[str, Any] = field(default_factory=dict)

@dataclass
class ParsedDocument:
    file_path: str
    file_name: str
    extension: str
    file_size: int
    last_modified: float
    content_hash: str
    full_text: str
    pages: List[DocumentPage] = field(default_factory=list)
    image_hash: Optional[str] = None
    is_scanned: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)

class BaseParser:
    def can_parse(self, file_path: Path) -> bool:
        raise NotImplementedError

    def parse(self, file_path: Path) -> ParsedDocument:
        raise NotImplementedError
