# Release gate tools

The verifier checks signed input evidence; it does not invoke a scanner, create a production signature, or establish GA acceptance by itself.

| File | Purpose and version |
| --- | --- |
| production-report.schema.json | JSON Schema for normalized dependency/container scan reports, schemaVersion 1 |
| release-manifest.schema.json | JSON Schema for the complete signed release manifest, schemaVersion 2 |
| verify-release.mjs | Manual structural and semantic checks, signature/hash verification, expected release identity and waiver enforcement |
| release-gate.test.mjs | Regression tests with real temporary Ed25519 keys and fixture scanner reports |
| schema-contract-test.py | Structural regression through the actual Draft 2020-12 validator and date-time format checker |
| backup-restore-smoke.mjs | Two fixture JSON files archived and restored with size/checksum comparison |

The verifier now requires seven CLI parameters: --artifact, --manifest, --public-key, --signature, --dependency-report, --container-report, and --expected-source-commit. The first six are paths. The expected commit is a lowercase 40- or 64-character Git object ID string; it is never resolved as a filesystem path. --waiver-public-key remains optional unless a report uses a waiver.

For example, from a trusted release checkout in PowerShell:

    $releaseExpectedCommit = (git rev-parse --verify HEAD).Trim()
    node scripts/release/verify-release.mjs --artifact release/package.jar --manifest release/manifest.json --public-key C:/trusted-release-config/release-public.pem --signature release/manifest.sig --dependency-report release/reports/dependency.json --container-report release/reports/container.json --expected-source-commit $releaseExpectedCommit

Provide the expected commit from the trusted release job or verified checkout state. Do not read it from the candidate manifest or report: that would defeat the external identity check. Pin the public keys in trusted release configuration, outside the untrusted candidate bundle. A valid signature for another commit is rejected even when all candidate reports agree with that other commit. The new required argument is an intentional CLI compatibility change; update all callers.

Schema files express required properties, types, enumerations, hash/commit formats, timestamp shape and waiver structure. Use a Draft 2020-12 validator with date-time format checking when validating them. verify-release.mjs currently performs its own checks and does not load or execute these JSON Schemas. Schema validation is not automatically wired into the verifier or a release pipeline by the presence of these files.

The runtime additionally validates the raw manifest signature, pinned key types, source commit equality with the trusted caller parameter, report/manifest/artifact bindings, file hashes and sizes, real filesystem containment, direct symlinks, duplicate paths, freshness, high/critical policy and independently signed waiver approvals. Paths inside the manifest resolve relative to its directory, even if the caller uses a different working directory. Signing must cover the complete final raw manifest bytes; the old schemaVersion 1 manifest signature contract is rejected. Scan report schemaVersion remains 1.

A generatedBy label, positive scannedTargets count, or even a signed report cannot prove a scanner ran unless the trusted producing pipeline actually executes it, captures its real output, and binds that evidence to the release. Control access to the signing service and protect both public-key configuration and expected commit input. Waiver approval identity and ticket authorization also require the independent approval service.

Run the cryptographic gate regression with:

    node --test scripts/release/release-gate.test.mjs

Run the independent structural contract tests in an isolated Python test environment with jsonschema and its date-time format dependency installed:

    python -m pip install -r scripts/release/schema-test-requirements.txt
    python scripts/release/schema-contract-test.py

The Schema tests invoke jsonschema.Draft202012Validator with FormatChecker and validate both Schema definitions plus positive/negative documents. Missing validator dependencies fail the test import instead of silently skipping validation. This separate test command does not integrate a Schema engine into verify-release.mjs or a production pipeline.

The Ubuntu/Windows client-quality jobs in .github/workflows/ci.yml and .github/workflows/desktop-release.yml install schema-test-requirements.txt, run schema-contract-test.py, exercise this verifier with node --test scripts/release/release-gate.test.mjs, and then run the unified Node regressions. Desktop packaging depends on both client-quality and browser-contracts. These are contract fixture checks; they do not execute production scanners, platform signing or database restoration. Their configuration does not establish that a remote workflow has run successfully.

The backup smoke can be run separately:

    node scripts/release/backup-restore-smoke.mjs

The backup smoke checks only the successful local archive round trip for two synthetic files. It does not exercise a real MySQL backup/restore, object storage, point-in-time recovery, corruption recovery, or disaster recovery. It is not evidence that a production restore succeeded, and this README does not claim either tool is connected to every release candidate job.

See RELEASE-GATE-CONTRACT.md for the complete field, trust and runtime boundary contract. Real scanner runs, controlled production signatures, database/object-store restoration logs, and deployment-specific operational drills remain separate acceptance evidence.
