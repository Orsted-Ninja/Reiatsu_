import re
from typing import List, Set, Tuple

# Mapping of canonical document identity concepts to their common phonetic, 
# bureaucratic, and institutional term variants in India and globally.
IDENTITY_EXPANSIONS = {
    "aadhar": {
        "aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "e-aadhaar", 
        "uidai", "unique identification", "resident identification"
    },
    "aadhaar": {
        "aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "e-aadhaar", 
        "uidai", "unique identification", "resident identification"
    },
    "adhar": {
        "aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "e-aadhaar", 
        "uidai", "unique identification"
    },
    "uidai": {
        "aadhar", "aadhaar", "adhar", "uidai", "unique identification"
    },
    "pan": {
        "pan", "pan card", "income tax department", "nsdl", "utiitsl", "permanent account number"
    },
    "passport": {
        "passport", "republic of india", "ministry of external affairs", "passport no"
    },
    "voter": {
        "voter", "voter id", "epic", "election commission of india", "elector", "electoral"
    },
    "license": {
        "license", "licence", "driving license", "driving licence", "motor vehicles", "rto"
    },
    "licence": {
        "license", "licence", "driving license", "driving licence", "motor vehicles", "rto"
    },
    "marksheet": {
        "marksheet", "mark sheet", "grade card", "marks list", "semester grade", "transcript"
    },
    "resume": {
        "resume", "cv", "curriculum vitae", "experience", "education", "skills"
    }
}

# Regex pattern for 12-digit Indian Aadhaar number (first digit is always 2-9 per UIDAI standard)
AADHAAR_REGEX = re.compile(r'\b[2-9]\d{3}\s?\d{4}\s?\d{4}\b')

# Regex pattern for 10-character Indian PAN number (e.g. ABCDE1234F)
PAN_REGEX = re.compile(r'\b[A-Z]{5}[0-9]{4}[A-Z]\b', re.IGNORECASE)

def expand_identity_query(query: str) -> Tuple[List[str], Set[str]]:
    """
    Expands an incoming query with relevant synonyms, phonetic spellings,
    and associated document keywords if identity terms are detected.
    """
    clean = re.sub(r'[^\w\s]', ' ', query).lower().strip()
    tokens = clean.split()
    expanded_terms: Set[str] = set()
    is_identity_search = False

    for tok in tokens:
        if tok in IDENTITY_EXPANSIONS:
            is_identity_search = True
            expanded_terms.update(IDENTITY_EXPANSIONS[tok])

    # Also check multi-word triggers (e.g. "aadhar card")
    for key, variants in IDENTITY_EXPANSIONS.items():
        if key in clean:
            is_identity_search = True
            expanded_terms.update(variants)

    return list(expanded_terms), expanded_terms


def get_identity_constraints(query: str):
    """
    Analyzes a query to see if the user is targeting a specific identity, official, 
    or bureaucratic document (PAN, Aadhaar, Passport, Driving License, Voter ID).
    Returns required keyword sets and validation regex to strictly eliminate false-positive
    matches (such as election training manuals or random forms matching 'card' or 'number').
    """
    clean = re.sub(r'[^\w\s]', ' ', query).lower()
    tokens = set(clean.split())

    # 1. PAN Card
    if ("pan" in tokens or "pancard" in tokens or "nsdl" in tokens or "utiitsl" in tokens
        or "pan card" in clean or "permanent account" in clean):
        return {
            "identity_type": "PAN Card",
            "required_terms": {"pan", "pancard", "utiitsl", "nsdl", "income tax", "permanent account"},
            "regex": PAN_REGEX
        }

    # 2. Aadhaar Card
    if any(t in tokens for t in ["aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "uidai"]) or "unique identification" in clean:
        return {
            "identity_type": "Aadhaar Card",
            "required_terms": {"aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "e-aadhaar", "uidai", "unique identification"},
            "regex": AADHAAR_REGEX
        }

    # 3. Passport
    if "passport" in tokens or "passport" in clean:
        return {
            "identity_type": "Passport",
            "required_terms": {"passport"},
            "regex": None
        }

    # 4. Driving License
    if "driving" in tokens or "license" in tokens or "licence" in tokens or "dl" in tokens:
        if "driving" in tokens or "dl" in tokens or "driving license" in clean or "driving licence" in clean:
            return {
                "identity_type": "Driving License",
                "required_terms": {"driving license", "driving licence", "motor vehicles", "transport department", "driving", "licence", "license"},
                "regex": None
            }

    # 5. Voter ID / EPIC
    if "voter" in tokens or "epic" in tokens or "elector" in tokens:
        return {
            "identity_type": "Voter ID",
            "required_terms": {"voter", "epic", "elector", "electoral", "election commission"},
            "regex": None
        }

    return None
