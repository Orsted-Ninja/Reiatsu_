import re
from typing import List, Set, Tuple, Dict, Any, Optional
from dataclasses import dataclass, field

# Common document container / artifact format nouns.
# On their own, these words carry almost zero domain-identifying power
# and MUST NOT be used as the sole basis for matching a document.
CONTAINER_WORDS: Set[str] = {
    "card", "number", "no", "num", "id", "bill", "invoice", "receipt",
    "report", "summary", "statement", "slip", "payslip", "paper", "papers",
    "manual", "handbook", "guide", "notes", "lecture", "module", "assignment", "lab", "test",
    "agreement", "contract", "nda", "deed", "lease", "letter",
    "form", "application", "sheet", "marksheet", "certificate",
    "presentation", "slides", "deck", "ppt", "doc", "document", "file",
    "draft", "copy", "scan", "scanned", "record", "records", "data", "list"
}

# Conversational stop words and fillers that carry no search information
STOP_WORDS: Set[str] = {
    "i", "me", "my", "myself", "we", "our", "ours", "you", "your", "yours",
    "he", "him", "his", "she", "her", "it", "its", "they", "them", "their",
    "what", "which", "who", "whom", "this", "that", "these", "those",
    "am", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "having", "do", "does", "did", "doing",
    "a", "an", "the", "and", "but", "if", "or", "because", "as", "until", "while",
    "of", "at", "by", "for", "with", "about", "against", "between", "into",
    "through", "during", "before", "after", "above", "below", "to", "from",
    "up", "down", "in", "out", "on", "off", "over", "under", "again", "further",
    "then", "once", "here", "there", "when", "where", "why", "how",
    "all", "any", "both", "each", "few", "more", "most", "other", "some", "such",
    "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
    "can", "will", "just", "don", "should", "now", "find", "show", "give",
    "tell", "know", "please", "stored", "keep", "locate", "fetch", "check"
}

# Conversational sentence prefixes to strip
STOP_PREFIXES = [
    "what is my", "what's my", "what are the", "where is my", "where did i store",
    "where did i keep", "where are my", "where are the", "find my", "find the",
    "can you find", "could you show", "please show me", "i need to find",
    "search for", "look for", "do i have", "tell me my", "show me my", "show me"
]

# Month and temporal modifiers
MONTH_WORDS: Set[str] = {
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
    "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec"
}


@dataclass
class QueryRoles:
    raw_query: str
    cleaned_query: str
    anchors: List[str] = field(default_factory=list)
    containers: List[str] = field(default_factory=list)
    modifiers: List[str] = field(default_factory=list)
    phrases: List[str] = field(default_factory=list)
    all_keywords: List[str] = field(default_factory=list)

    @property
    def has_anchors(self) -> bool:
        return len(self.anchors) > 0


class QueryRoleParser:
    """
    Sub-millisecond linguistic role parser that decomposes search queries into:
    1. Core Anchors (high-salience topics/entities e.g. 'pan', 'electricity', 'thyroid', 'cet415')
    2. Container/Artifact Types (generic document forms e.g. 'card', 'bill', 'report', 'manual')
    3. Modifiers (temporal/numeric tokens e.g. 'march', '2024', 'sem 4')
    4. Exact Phrases (quoted or multi-word continuous shingles)
    """

    @classmethod
    def parse(cls, query: str) -> QueryRoles:
        raw = query.strip()
        phrases: List[str] = [p.lower().strip() for p in re.findall(r'"([^"]+)"', raw) if len(p.strip()) >= 2]
        unquoted = re.sub(r'"[^"]+"', ' ', raw)

        # Strip explicit drive patterns so they don't pollute keyword roles
        unquoted = re.sub(r'\b(?:in|on|from)\s+[A-Za-z]\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', ' ', unquoted, flags=re.I)
        unquoted = re.sub(r'\b[A-Za-z]\s*(?::(?:\\|/)?|\s+drive)(?=\s|$|[,\.;])', ' ', unquoted, flags=re.I)
        unquoted = re.sub(r'\b[A-Za-z]:[\\/]?', ' ', unquoted)

        clean = re.sub(r'[^\w\s]', ' ', unquoted).lower().strip()

        # Strip conversational question prefixes
        for prefix in STOP_PREFIXES:
            if clean.startswith(prefix):
                clean = clean[len(prefix):].strip()
                break

        tokens = clean.split()
        anchors: List[str] = []
        containers: List[str] = []
        modifiers: List[str] = []

        # Multi-word shingles (4-5 words) for first-page paragraph excerpts
        if len(tokens) >= 4:
            for shingle_len in [4, 5]:
                for idx in range(0, len(tokens) - shingle_len + 1):
                    shingle = " ".join(tokens[idx:idx + shingle_len])
                    if shingle not in phrases and len(shingle) >= 12:
                        phrases.append(shingle)

        for tok in tokens:
            if tok in STOP_WORDS or len(tok) <= 1:
                continue

            # Check alphanumeric code (e.g. cet415, cs301)
            m_code = re.match(r'^([a-zA-Z]{2,})(\d+)$', tok)
            if m_code:
                prefix, num = m_code.groups()
                anchors.append(tok)
                p1 = f"{prefix} {num}"
                p2 = f"{prefix}-{num}"
                if p1 not in phrases:
                    phrases.append(p1)
                if p2 not in phrases:
                    phrases.append(p2)
                if prefix not in anchors and prefix not in STOP_WORDS and len(prefix) > 1:
                    anchors.append(prefix)
                if num not in modifiers:
                    modifiers.append(num)
                continue

            # Check if temporal/numeric modifier
            if tok in MONTH_WORDS or tok.isdigit() or re.match(r'^(?:19|20)\d{2}$', tok):
                modifiers.append(tok)
                continue

            # Check if generic container/artifact type
            if tok in CONTAINER_WORDS:
                containers.append(tok)
                continue

            # Otherwise, the token is a core anchor entity/subject!
            anchors.append(tok)

        # Deduplicate while preserving order
        def _dedup(seq):
            seen = set()
            return [x for x in seq if not (x in seen or seen.add(x))]

        anchors = _dedup(anchors)
        containers = _dedup(containers)
        modifiers = _dedup(modifiers)
        phrases = _dedup(phrases)

        all_keywords = _dedup(anchors + containers + modifiers)

        return QueryRoles(
            raw_query=raw,
            cleaned_query=clean,
            anchors=anchors,
            containers=containers,
            modifiers=modifiers,
            phrases=phrases,
            all_keywords=all_keywords
        )
