import re
from typing import Dict, Any, Optional, Set, List, Tuple
from dataclasses import dataclass, field
from core.query_parser import QueryRoles


@dataclass
class OntologyEntity:
    key: str
    domain: str
    display_badge: str
    trigger_tokens: Set[str]
    anchor_synonyms: Set[str]
    container_types: Set[str] = field(default_factory=set)
    validation_regex: Optional[re.Pattern] = None
    exclusion_patterns: List[str] = field(default_factory=list)


# 1. Identity Regexes
AADHAAR_REGEX = re.compile(r'\b[2-9]\d{3}\s?\d{4}\s?\d{4}\b')
PAN_REGEX = re.compile(r'\b[A-Z]{5}[0-9]{4}[A-Z]\b', re.I)
PASSPORT_REGEX = re.compile(r'\b[A-Z][0-9]{7}\b', re.I)

# 2. Financial Regexes
IFSC_REGEX = re.compile(r'\b[A-Z]{4}0[A-Z0-9]{6}\b', re.I)
ITR_REGEX = re.compile(r'\b(?:itr-[1-7]|assessment year|ay\s*20\d{2}-\d{2}|acknowledgement number)\b', re.I)

# Complete Declarative Domain Ontology Registry
ONTOLOGY_REGISTRY: Dict[str, OntologyEntity] = {
    # ---------------- 1. IDENTITY & CITIZEN RECORDS ----------------
    "pan": OntologyEntity(
        key="pan",
        domain="Identity",
        display_badge="PAN Card",
        trigger_tokens={"pan", "pancard", "utiitsl", "nsdl"},
        anchor_synonyms={"pan", "pancard", "income tax department", "utiitsl", "nsdl", "permanent account number"},
        container_types={"card", "number", "form", "copy", "doc"},
        validation_regex=PAN_REGEX,
        exclusion_patterns=["faq election", "polling officials", "election duty", "training manual", "panasonic", "pantry"]
    ),
    "aadhaar": OntologyEntity(
        key="aadhaar",
        domain="Identity",
        display_badge="Aadhaar Card",
        trigger_tokens={"aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "uidai"},
        anchor_synonyms={"aadhar", "aadhaar", "adhar", "adhaar", "eaadhaar", "e-aadhaar", "uidai", "unique identification", "resident identification"},
        container_types={"card", "number", "letter", "doc"},
        validation_regex=AADHAAR_REGEX,
        exclusion_patterns=["credit card", "debit card", "atm card"]
    ),
    "passport": OntologyEntity(
        key="passport",
        domain="Identity",
        display_badge="Passport",
        trigger_tokens={"passport"},
        anchor_synonyms={"passport", "ministry of external affairs", "republic of india", "passport no", "passport number"},
        container_types={"doc", "copy", "scan"},
        validation_regex=PASSPORT_REGEX,
        exclusion_patterns=["boarding pass", "password", "passage"]
    ),
    "driving_license": OntologyEntity(
        key="driving_license",
        domain="Identity",
        display_badge="Driving License",
        trigger_tokens={"license", "licence", "dl"},
        anchor_synonyms={"driving license", "driving licence", "motor vehicles", "transport department", "sarathi", "rto"},
        container_types={"card", "number", "doc"},
        validation_regex=None,
        exclusion_patterns=["software license", "mit license", "apache license", "gpl license"]
    ),
    "voter_id": OntologyEntity(
        key="voter_id",
        domain="Identity",
        display_badge="Voter ID (EPIC)",
        trigger_tokens={"voter", "epic"},
        anchor_synonyms={"voter id", "voter card", "epic", "election commission of india", "elector photo", "electoral roll"},
        container_types={"card", "number", "form"},
        validation_regex=None,
        exclusion_patterns=["polling duty training", "handbook for presiding"]
    ),

    # ---------------- 2. FINANCIAL & TAXATION ----------------
    "bank_statement": OntologyEntity(
        key="bank_statement",
        domain="Financial",
        display_badge="Bank Statement",
        trigger_tokens={"bank", "statement", "passbook", "hdfc", "sbi", "icici", "axis", "kotak", "canara", "pnb"},
        anchor_synonyms={"bank statement", "account statement", "savings account", "current account", "passbook", "opening balance", "closing balance", "ifsc code"},
        container_types={"statement", "summary", "report", "doc"},
        validation_regex=IFSC_REGEX,
        exclusion_patterns=["project statement", "problem statement", "thesis statement"]
    ),
    "salary_slip": OntologyEntity(
        key="salary_slip",
        domain="Financial",
        display_badge="Salary Slip / Payslip",
        trigger_tokens={"salary", "payslip", "pay", "earnings"},
        anchor_synonyms={"salary slip", "pay slip", "payslip", "earnings statement", "basic pay", "gross salary", "net salary", "ctc breakdown"},
        container_types={"slip", "statement", "receipt", "doc"},
        validation_regex=None,
        exclusion_patterns=[]
    ),
    "tax_itr": OntologyEntity(
        key="tax_itr",
        domain="Financial",
        display_badge="ITR Tax Filing",
        trigger_tokens={"itr", "tax", "form16", "26as"},
        anchor_synonyms={"income tax return", "itr", "itr-v", "form 16", "form 26as", "assessment year", "gross total income"},
        container_types={"filing", "acknowledgement", "form", "statement", "receipt"},
        validation_regex=ITR_REGEX,
        exclusion_patterns=[]
    ),

    # ---------------- 3. UTILITIES & BILLS ----------------
    "electricity_bill": OntologyEntity(
        key="electricity_bill",
        domain="Utility",
        display_badge="Electricity Bill",
        trigger_tokens={"electricity", "power", "bescom", "tneb", "mseb", "cesc", "kptcl", "uppcl"},
        anchor_synonyms={"electricity bill", "electric bill", "power bill", "bescom", "tneb", "mseb", "cesc", "kptcl", "consumer number", "kwh", "meter reading"},
        container_types={"bill", "receipt", "invoice"},
        validation_regex=None,
        exclusion_patterns=["telephone bill", "phone bill", "water bill", "credit card bill"]
    ),
    "water_bill": OntologyEntity(
        key="water_bill",
        domain="Utility",
        display_badge="Water Utility Bill",
        trigger_tokens={"water", "bwssb", "djb"},
        anchor_synonyms={"water bill", "water supply", "sewerage", "bwssb", "delhi jal board", "consumer no"},
        container_types={"bill", "receipt"},
        validation_regex=None,
        exclusion_patterns=["electricity bill", "power bill"]
    ),

    # ---------------- 4. HEALTHCARE & MEDICAL ----------------
    "medical_lab_report": OntologyEntity(
        key="medical_lab_report",
        domain="Healthcare",
        display_badge="Medical Diagnostic Report",
        trigger_tokens={"blood", "thyroid", "lipid", "prescription", "diagnostic", "clinic", "hospital", "pathology"},
        anchor_synonyms={"blood test", "lab report", "thyroid", "lipid profile", "haemoglobin", "cbc", "prescription", "discharge summary", "reference range", "pathology", "diagnostic"},
        container_types={"report", "summary", "test", "prescription", "slip"},
        validation_regex=None,
        exclusion_patterns=["unit test report", "software test", "test suite", "integration test"]
    ),

    # ---------------- 5. LEGAL & CONTRACTS ----------------
    "rent_agreement": OntologyEntity(
        key="rent_agreement",
        domain="Legal",
        display_badge="Rent / Lease Agreement",
        trigger_tokens={"rent", "rental", "lease", "tenancy", "tenant", "landlord"},
        anchor_synonyms={"rent agreement", "rental agreement", "lease agreement", "residential rental", "tenancy agreement", "lessor", "lessee", "security deposit", "monthly rent"},
        container_types={"agreement", "contract", "deed", "doc"},
        validation_regex=None,
        exclusion_patterns=["software license agreement", "non-disclosure agreement", "nda agreement", "employment agreement"]
    ),
    "employment_contract": OntologyEntity(
        key="employment_contract",
        domain="Legal",
        display_badge="Employment / Offer Letter",
        trigger_tokens={"offer", "relieving", "experience", "appointment", "probation"},
        anchor_synonyms={"offer letter", "letter of appointment", "relieving letter", "experience certificate", "employment agreement", "date of joining"},
        container_types={"letter", "agreement", "certificate", "doc"},
        validation_regex=None,
        exclusion_patterns=[]
    ),

    # ---------------- 6. ACADEMIC & EDUCATION ----------------
    "academic_transcript": OntologyEntity(
        key="academic_transcript",
        domain="Academic",
        display_badge="Marksheet / Academic Grade Card",
        trigger_tokens={"marksheet", "grade", "transcript", "cgpa", "sgpa", "semester"},
        anchor_synonyms={"marksheet", "mark sheet", "grade card", "academic transcript", "grade report", "semester grade", "credit points", "provisional certificate"},
        container_types={"sheet", "card", "transcript", "certificate", "report"},
        validation_regex=None,
        exclusion_patterns=[]
    )
}


class DocumentOntology:
    """
    Extensible declarative document ontology engine.
    Matches queries to canonical entity definitions and validates candidate documents.
    """

    @classmethod
    def match_entity(cls, query: str, roles: QueryRoles) -> Optional[OntologyEntity]:
        """
        Determines if the query intent matches an established domain ontology entity.
        Requires that either:
        1. An entity's trigger tokens match an Anchor term, OR
        2. A multi-word trigger exists in the cleaned query string.
        """
        clean_q = roles.cleaned_query.lower()
        all_query_terms = set(roles.anchors + roles.containers + roles.modifiers)

        for entity in ONTOLOGY_REGISTRY.values():
            # Check if any trigger token is in anchors
            if any(t in roles.anchors for t in entity.trigger_tokens):
                return entity

            # Check if multi-word trigger exists in cleaned query
            for syn in entity.anchor_synonyms:
                if " " in syn and syn in clean_q:
                    return entity

            # Check if trigger token matches container combined with query
            for trig in entity.trigger_tokens:
                if trig in all_query_terms and any(c in roles.containers for c in entity.container_types):
                    return entity

        return None

    @classmethod
    def validate_candidate(
        cls,
        text: str,
        filename: str,
        entity: OntologyEntity
    ) -> Tuple[bool, bool, Optional[str]]:
        """
        Validates whether a candidate document's text/filename genuinely satisfies
        the ontology entity's strict domain requirements.
        Returns: (is_qualified, is_regex_verified, best_snippet)
        """
        text_lower = text.lower()
        fn_lower = filename.lower()
        combined = f"{fn_lower} {text_lower}"

        # 1. Check exclusions (e.g. software test report matching blood test report, or election manual matching PAN)
        for excl in entity.exclusion_patterns:
            if excl in fn_lower or excl in text_lower[:2000]:
                return False, False, None

        # 2. Check canonical regex validation (e.g. PAN 10-char or Aadhaar 12-digit format)
        if entity.validation_regex:
            m_reg = entity.validation_regex.search(text)
            if m_reg:
                match_val = m_reg.group(0)
                pos = text.find(match_val)
                start = max(0, pos - 60)
                end = min(len(text), pos + 120)
                snip = text[start:end].replace("\n", " ").strip()
                return True, True, f"[VERIFIED {entity.display_badge.upper()}] ...{snip}..."

        # 3. Check anchor synonyms with strict word boundaries
        has_anchor_hit = False
        first_syn = None

        for syn in entity.anchor_synonyms:
            if " " in syn:
                if syn in combined:
                    has_anchor_hit = True
                    first_syn = syn
                    break
            else:
                if re.search(rf'\b{re.escape(syn)}\b', combined):
                    has_anchor_hit = True
                    first_syn = syn
                    break

        if not has_anchor_hit:
            # Crucial: Disqualify candidate! Does NOT contain required entity anchors!
            return False, False, None

        # Extract contextual snippet around the matched anchor term
        pos = text_lower.find(first_syn) if first_syn in text_lower else 0
        start = max(0, pos - 60)
        end = min(len(text), pos + 120)
        snip = text[start:end].replace("\n", " ").strip() if text else filename
        return True, False, f"[{entity.display_badge.upper()}] ...{snip}..."
