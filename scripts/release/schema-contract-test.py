"""Validate the published structures with the actual Draft 2020-12 engine.

Test dependency: jsonschema[format-nongpl]==4.23.0.
These synthetic documents do not represent scanner or production signature evidence.
"""
import base64
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parent
REPORT_SCHEMA = json.loads((ROOT / "production-report.schema.json").read_text("utf-8"))
MANIFEST_SCHEMA = json.loads((ROOT / "release-manifest.schema.json").read_text("utf-8"))
CHECKER = FormatChecker()
REPORT = Draft202012Validator(REPORT_SCHEMA, format_checker=CHECKER)
MANIFEST = Draft202012Validator(MANIFEST_SCHEMA, format_checker=CHECKER)
TIMESTAMP = "2026-11-17T01:23:45.000Z"
COMMIT = "a" * 40
HASH = "b" * 64


def report():
    return {
        "schemaVersion": 1,
        "kind": "dependency",
        "status": "pass",
        "generatedBy": "controlled-ci",
        "scanId": "fixture-only",
        "generatedAt": TIMESTAMP,
        "sourceCommit": COMMIT,
        "artifactSha256": HASH,
        "scannedTargets": 1,
        "scanner": {"name": "fixture", "version": "1", "databaseUpdatedAt": TIMESTAMP},
        "findings": [],
    }


def waiver():
    return {
        "reason": "Security reviewer approved the documented compensating control",
        "approvedBy": "fixture-approver",
        "ticket": "SEC-fixture",
        "expiresAt": TIMESTAMP,
        "approvalSignature": base64.b64encode(bytes(64)).decode("ascii"),
    }


def manifest():
    return {
        "schemaVersion": 2,
        "sourceCommit": COMMIT,
        "createdAt": TIMESTAMP,
        "files": [{"path": "package.jar", "sha256": HASH, "bytes": 12}],
        "scanReports": [
            {"kind": "dependency", "path": "reports/dependency.json", "sha256": HASH},
            {"kind": "container", "path": "reports/container.json", "sha256": HASH},
        ],
    }


class PublishedSchemaContractTest(unittest.TestCase):
    def invalid(self, validator, document):
        self.assertTrue(list(validator.iter_errors(document)), "invalid fixture unexpectedly passed")

    def test_01_schemas_are_valid_draft_2020_12(self):
        Draft202012Validator.check_schema(REPORT_SCHEMA)
        Draft202012Validator.check_schema(MANIFEST_SCHEMA)

    def test_02_dependency_and_container_reports(self):
        for kind in ("dependency", "container"):
            document = report()
            document["kind"] = kind
            REPORT.validate(document)

    def test_03_required_report_properties(self):
        for field in REPORT_SCHEMA["required"]:
            with self.subTest(field=field):
                document = report()
                del document[field]
                self.invalid(REPORT, document)

    def test_04_required_scanner_metadata(self):
        for field in ("name", "version", "databaseUpdatedAt"):
            for value in (None, "", "   "):
                with self.subTest(field=field, value=value):
                    document = report()
                    document["scanner"][field] = value
                    self.invalid(REPORT, document)

    def test_05_commit_format(self):
        for validator, factory in ((REPORT, report), (MANIFEST, manifest)):
            for value in ("main", "A" * 40, "a" * 39, "a" * 41, "g" * 40,
                          "a" * 63, "a" * 65, "a" * 40 + chr(10), "a" * 64 + chr(13)):
                with self.subTest(value=repr(value)):
                    document = factory()
                    document["sourceCommit"] = value
                    self.invalid(validator, document)
            document = factory()
            document["sourceCommit"] = "c" * 64
            validator.validate(document)

    def test_06_report_hash_format(self):
        for value in ("B" * 64, "b" * 63, "b" * 65, "b" * 64 + chr(10), None):
            document = report()
            document["artifactSha256"] = value
            self.invalid(REPORT, document)

    def test_07_actual_coverage_claim_must_be_positive_safe_integer(self):
        for value in (0, -1, 1.5, True, "1", 9007199254740992):
            document = report()
            document["scannedTargets"] = value
            self.invalid(REPORT, document)

    def test_08_report_enums_and_versions(self):
        for field, value in (("kind", "browser"), ("kind", "DEPENDENCY"),
                             ("schemaVersion", 2), ("status", "success"),
                             ("generatedBy", "local-self-report")):
            document = report()
            document[field] = value
            self.invalid(REPORT, document)

    def test_09_timestamp_format_and_calendar(self):
        for value in ("2026-11-17", "2026-11-17T01:23:45Z",
                      "2026-11-17T09:23:45.000+08:00", "2026-11-32T01:23:45.000Z",
                      "2026-11-17T25:23:45.000Z", TIMESTAMP + chr(10)):
            document = report()
            document["generatedAt"] = value
            self.invalid(REPORT, document)
            document = manifest()
            document["createdAt"] = value
            self.invalid(MANIFEST, document)

    def test_10_finding_structure(self):
        for value in ("critical", "high", "medium", "low", "info"):
            document = report()
            document["findings"] = [{"id": "CVE-fixture", "severity": value, "status": "open"}]
            REPORT.validate(document)
        for finding in (None, {}, {"id": "x", "severity": "HIGH", "status": "open"},
                        {"id": "x", "severity": "high", "status": "resolved"},
                        {"id": "   ", "severity": "high", "status": "open"}):
            document = report()
            document["findings"] = [finding]
            self.invalid(REPORT, document)

    def test_11_active_waiver_requires_each_field(self):
        finding = {"id": "CVE-fixture", "severity": "high",
                   "status": "ignored-with-expiry", "waiver": waiver()}
        document = report()
        document["findings"] = [finding]
        REPORT.validate(document)
        for field in ("reason", "approvedBy", "ticket", "expiresAt", "approvalSignature"):
            invalid = copy.deepcopy(document)
            del invalid["findings"][0]["waiver"][field]
            self.invalid(REPORT, invalid)
        del document["findings"][0]["waiver"]
        self.invalid(REPORT, document)

    def test_12_waiver_text_and_encoding(self):
        for field, value in (("reason", "short"), ("reason", " " * 40),
                             ("reason", "a" * 15 + " " * 10),
                             ("approvedBy", " "), ("ticket", ""),
                             ("approvalSignature", "not-base64"),
                             ("approvalSignature", base64.b64encode(bytes(63)).decode("ascii"))):
            document = report()
            current = waiver()
            current[field] = value
            document["findings"] = [{"id": "CVE-fixture", "severity": "high",
                                     "status": "ignored-with-expiry", "waiver": current}]
            self.invalid(REPORT, document)

    def test_13_manifest_version_2_with_both_reports(self):
        MANIFEST.validate(manifest())
        document = manifest()
        document["schemaVersion"] = 1
        self.invalid(MANIFEST, document)

    def test_14_required_manifest_properties(self):
        for field in MANIFEST_SCHEMA["required"]:
            document = manifest()
            del document[field]
            self.invalid(MANIFEST, document)

    def test_15_scan_kind_coverage(self):
        for references in ([], [manifest()["scanReports"][0]],
                           [manifest()["scanReports"][0]] * 2,
                           manifest()["scanReports"] * 2):
            document = manifest()
            document["scanReports"] = references
            self.invalid(MANIFEST, document)
        document = manifest()
        document["scanReports"][0]["kind"] = "DEPENDENCY"
        self.invalid(MANIFEST, document)

    def test_16_file_sizes_and_hashes(self):
        for field, value in (("bytes", -1), ("bytes", True), ("bytes", 1.5),
                             ("bytes", 9007199254740992), ("sha256", "x" * 64),
                             ("sha256", HASH + chr(10))):
            document = manifest()
            document["files"][0][field] = value
            self.invalid(MANIFEST, document)
        document = manifest()
        document["files"] = []
        self.invalid(MANIFEST, document)
        document = manifest()
        document["files"] *= 2
        self.invalid(MANIFEST, document)

    def test_17_relative_paths(self):
        for value in ("/absolute.jar", "../package.jar", "a/../package.jar", "./package.jar",
                      "a/./package.jar", "a//package.jar", "a/", "D:/package.jar",
                      "a" + chr(92) + "package.jar", "a" + chr(0) + "b", ""):
            with self.subTest(path=repr(value)):
                for key in ("files", "scanReports"):
                    document = manifest()
                    document[key][0]["path"] = value
                    self.invalid(MANIFEST, document)
        for value in ("package.jar", "nested/package.jar", "nested/中文.jar"):
            document = manifest()
            document["files"][0]["path"] = value
            MANIFEST.validate(document)

    def test_18_required_scan_reference_properties(self):
        for field in ("kind", "path", "sha256"):
            document = manifest()
            del document["scanReports"][0][field]
            self.invalid(MANIFEST, document)

    def test_19_extra_metadata_is_structurally_allowed_and_signed_at_runtime(self):
        document = manifest()
        document["releaseMetadata"] = {"fixture": True}
        MANIFEST.validate(document)

    def test_20_schema_does_not_claim_cryptographic_or_freshness_verification(self):
        document = report()
        document["generatedAt"] = "2000-01-01T00:00:00.000Z"
        document["findings"] = [{"id": "CVE-fixture", "severity": "critical",
                                 "status": "ignored-with-expiry", "waiver": waiver()}]
        REPORT.validate(document)


if __name__ == "__main__":
    unittest.main(verbosity=2)
