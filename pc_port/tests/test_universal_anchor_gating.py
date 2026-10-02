import unittest
import tempfile
from pathlib import Path
from core.query_parser import QueryRoleParser, QueryRoles
from core.document_ontology import DocumentOntology, ONTOLOGY_REGISTRY
from search.live_scanner import LiveFilesystemScanner


class TestUniversalAnchorGating(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.base = Path(self.temp_dir.name)
        self.scanner = LiveFilesystemScanner()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_query_role_decomposition(self):
        # 1. PAN query
        r_pan = QueryRoleParser.parse("what is my pan card number")
        self.assertIn("pan", r_pan.anchors)
        self.assertIn("card", r_pan.containers)
        self.assertIn("number", r_pan.containers)
        self.assertTrue(r_pan.has_anchors)

        # 2. Electricity bill query
        r_elec = QueryRoleParser.parse("find electricity bill for march 2024")
        self.assertIn("electricity", r_elec.anchors)
        self.assertIn("bill", r_elec.containers)
        self.assertIn("march", r_elec.modifiers)
        self.assertIn("2024", r_elec.modifiers)

        # 3. Medical blood test query
        r_med = QueryRoleParser.parse("where is my blood test report for thyroid")
        self.assertTrue("blood" in r_med.anchors or "thyroid" in r_med.anchors)
        self.assertIn("report", r_med.containers)

        # 4. Alphanumeric course code
        r_code = QueryRoleParser.parse("cet415 lab manual")
        self.assertIn("cet415", r_code.anchors)
        self.assertIn("manual", r_code.containers)

        # 5. Rent agreement
        r_rent = QueryRoleParser.parse("rent agreement signed last year")
        self.assertIn("rent", r_rent.anchors)
        self.assertIn("agreement", r_rent.containers)

    def test_document_ontology_entity_matching(self):
        # PAN match
        r1 = QueryRoleParser.parse("what is my pan card number")
        e1 = DocumentOntology.match_entity("what is my pan card number", r1)
        self.assertIsNotNone(e1)
        self.assertEqual(e1.key, "pan")

        # Electricity bill match
        r2 = QueryRoleParser.parse("electricity bill for august")
        e2 = DocumentOntology.match_entity("electricity bill for august", r2)
        self.assertIsNotNone(e2)
        self.assertEqual(e2.key, "electricity_bill")

        # Bank statement match
        r3 = QueryRoleParser.parse("hdfc bank statement")
        e3 = DocumentOntology.match_entity("hdfc bank statement", r3)
        self.assertIsNotNone(e3)
        self.assertEqual(e3.key, "bank_statement")

        # Medical report match
        r4 = QueryRoleParser.parse("blood test report thyroid")
        e4 = DocumentOntology.match_entity("blood test report thyroid", r4)
        self.assertIsNotNone(e4)
        self.assertEqual(e4.key, "medical_lab_report")

        # Rent agreement match
        r5 = QueryRoleParser.parse("rent agreement signed")
        e5 = DocumentOntology.match_entity("rent agreement signed", r5)
        self.assertIsNotNone(e5)
        self.assertEqual(e5.key, "rent_agreement")

    def test_utility_anchor_gating(self):
        # Telephone bill should NOT match electricity bill search
        phone_bill = self.base / "Telephone_Bill_March.txt"
        phone_bill.write_text("Airtel Broadband and Telephone Bill for March 2024. Total due: 999 INR.", encoding="utf-8")

        q = "find electricity bill for march"
        roles = QueryRoleParser.parse(q)
        entity = DocumentOntology.match_entity(q, roles)

        res_phone = self.scanner._inspect_file_content(
            phone_bill, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNone(res_phone, "Telephone bill must be rejected for electricity bill query!")

        # Authentic electricity bill
        elec_bill = self.base / "Power_Supply_Bill.txt"
        elec_bill.write_text("BESCOM Electricity Bill for March 2024. Consumer Number: 12345. Total kWh: 250 units.", encoding="utf-8")

        res_elec = self.scanner._inspect_file_content(
            elec_bill, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNotNone(res_elec, "BESCOM electricity bill must match!")
        score, snip = res_elec
        self.assertGreaterEqual(score, 0.09)
        self.assertIn("ELECTRICITY BILL", snip.upper())

    def test_medical_anchor_gating(self):
        # Software test report should NOT match blood test report
        soft_test = self.base / "Software_Unit_Test_Report.txt"
        soft_test.write_text("Test execution report for sprint 42. Total test cases: 150 passed, 0 failed.", encoding="utf-8")

        q = "where is my blood test report for thyroid"
        roles = QueryRoleParser.parse(q)
        entity = DocumentOntology.match_entity(q, roles)

        res_soft = self.scanner._inspect_file_content(
            soft_test, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNone(res_soft, "Software test report must be rejected for blood test query!")

        # Authentic thyroid report
        lab_report = self.base / "Diagnostic_Lab_Result.txt"
        lab_report.write_text("Clinical Laboratory Blood Test Report. Thyroid Stimulating Hormone (TSH): 2.45 uIU/mL (Reference range: 0.4 - 4.2).", encoding="utf-8")

        res_lab = self.scanner._inspect_file_content(
            lab_report, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNotNone(res_lab, "Authentic thyroid lab report must match!")
        score, snip = res_lab
        self.assertGreaterEqual(score, 0.09)
        self.assertIn("MEDICAL DIAGNOSTIC REPORT", snip.upper())

    def test_legal_agreement_anchor_gating(self):
        # NDA should NOT match rent agreement query
        nda_file = self.base / "Employee_NDA_Agreement.txt"
        nda_file.write_text("Mutual Non-Disclosure Agreement and Confidentiality Contract signed between parties.", encoding="utf-8")

        q = "rent agreement signed"
        roles = QueryRoleParser.parse(q)
        entity = DocumentOntology.match_entity(q, roles)

        res_nda = self.scanner._inspect_file_content(
            nda_file, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNone(res_nda, "NDA agreement must be rejected for rent agreement query!")

        # Authentic rent agreement
        rent_file = self.base / "Flat_Tenancy_Contract.txt"
        rent_file.write_text("Residential Rental Agreement entered into between Landlord and Tenant. Monthly rent: 22,000 INR.", encoding="utf-8")

        res_rent = self.scanner._inspect_file_content(
            rent_file, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNotNone(res_rent, "Authentic residential rent agreement must match!")
        score, snip = res_rent
        self.assertGreaterEqual(score, 0.09)
        self.assertIn("RENT", snip.upper())

    def test_academic_course_code_anchor_gating(self):
        # Generic python lab manual should NOT match cet415 lab manual
        gen_lab = self.base / "Python_Lab_Manual.txt"
        gen_lab.write_text("Laboratory manual for python programming course. Exercise 1: Lists and Dictionaries.", encoding="utf-8")

        q = "cet415 lab manual"
        roles = QueryRoleParser.parse(q)
        entity = DocumentOntology.match_entity(q, roles)

        res_gen = self.scanner._inspect_file_content(
            gen_lab, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNone(res_gen, "Generic lab manual lacking 'cet415' anchor must be rejected!")

        # Authentic cet415 lab manual
        cet_lab = self.base / "Cloud_Computing_Lab.txt"
        cet_lab.write_text("APJ Abdul Kalam Technological University. CET415 Cloud Computing and Virtualization Lab Manual.", encoding="utf-8")

        res_cet = self.scanner._inspect_file_content(
            cet_lab, keywords=roles.all_keywords, phrases=roles.phrases,
            roles=roles, ontology_entity=entity
        )
        self.assertIsNotNone(res_cet, "CET415 lab manual must match!")
        score, snip = res_cet
        self.assertGreaterEqual(score, 0.04)


if __name__ == "__main__":
    unittest.main()
