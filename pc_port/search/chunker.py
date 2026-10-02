from dataclasses import dataclass
from typing import List
import re

@dataclass
class TextChunk:
    chunk_id: str
    file_path: str
    file_name: str
    chunk_index: int
    page_number: int
    text: str
    token_count: int

class RecursiveChunker:
    def __init__(self, target_tokens: int = 350, overlap_tokens: int = 70):
        self.target_tokens = target_tokens
        self.overlap_tokens = overlap_tokens

    def estimate_tokens(self, text: str) -> int:
        # Fast approximation: ~4 chars per token in English
        return max(1, len(text.split()))

    def _slice_tokens(self, tokens: List[str]) -> List[List[str]]:
        slices = []
        step = max(1, self.target_tokens - self.overlap_tokens)
        for i in range(0, len(tokens), step):
            win = tokens[i:i + self.target_tokens]
            if win:
                slices.append(win)
        return slices

    def chunk_text(self, text: str, file_path: str, file_name: str, page_number: int = 1) -> List[TextChunk]:
        if not text.strip():
            return []

        # Split hierarchically: paragraphs (or double newlines)
        paragraphs = re.split(r'\n\s*\n', text)
        chunks: List[TextChunk] = []
        current_words: List[str] = []
        chunk_idx = 0

        def emit_chunk(words_to_emit: List[str]):
            nonlocal chunk_idx
            if not words_to_emit:
                return
            chunk_str = " ".join(words_to_emit)
            chunks.append(TextChunk(
                chunk_id=f"{file_path}#chunk_{chunk_idx}",
                file_path=file_path,
                file_name=file_name,
                chunk_index=chunk_idx,
                page_number=page_number,
                text=chunk_str,
                token_count=len(words_to_emit)
            ))
            chunk_idx += 1

        for para in paragraphs:
            para = para.strip()
            if not para:
                continue

            words = para.split()
            if len(current_words) + len(words) <= self.target_tokens:
                current_words.extend(words)
            else:
                if current_words:
                    emit_chunk(current_words)
                    overlap_count = min(self.overlap_tokens, len(current_words))
                    current_words = current_words[-overlap_count:] if overlap_count > 0 else []

                # If the single paragraph exceeds target_tokens, split by sentences or linebreaks
                if len(words) > self.target_tokens:
                    sentences = re.split(r'(?<=[.!?\n])\s+', para)
                    for sent in sentences:
                        sent_words = sent.split()
                        if not sent_words:
                            continue
                        if len(sent_words) > self.target_tokens:
                            # Hard slicing for monolithic code blocks or tables
                            slices = self._slice_tokens(sent_words)
                            for s in slices:
                                if current_words:
                                    emit_chunk(current_words)
                                    current_words = []
                                emit_chunk(s)
                        elif len(current_words) + len(sent_words) <= self.target_tokens:
                            current_words.extend(sent_words)
                        else:
                            emit_chunk(current_words)
                            overlap_count = min(self.overlap_tokens, len(current_words))
                            current_words = current_words[-overlap_count:] if overlap_count > 0 else []
                            current_words.extend(sent_words)
                else:
                    current_words.extend(words)

        if current_words:
            emit_chunk(current_words)

        return chunks
