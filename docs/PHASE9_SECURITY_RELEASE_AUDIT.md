# Phase 9 — Security, supply-chain and release audit

**Implementation status:** audit tooling and automation are added to the repository. Their presence does not mean that an independent security review, legal license approval or production release signing has been completed. CI evidence is retained as GitHub Actions artifacts; human reviews remain mandatory acceptance gates.

## 1. Software Bill of Materials

- CycloneDX creates an aggregate SBOM from Gradle's resolved dependency graph, including transitive dependencies.
- Syft creates CycloneDX SBOMs for the Go discovery service, Rust MLS library and Node companion after its pinned release checksum is verified.
- The workflow uploads SBOM artifacts.
- A repository script normalizes license metadata from all SBOMs into JSON and Markdown inventory reports.

## 2. License review

The inventory distinguishes:
- IDENTIFIER_FOUND: license metadata exists; this is not legal compatibility approval.
- REVIEW_REQUIRED: metadata is missing or the license family needs distribution review.

**Known item:** the Gradle dependency declaration identifies smack-omemo-signal as GPL-3.0. Automated metadata cannot decide the legal effect of linking or distribution. Record a legal decision before publishing binaries. The audit does not silently remove or replace this dependency.

## 3. Vulnerability and workflow checks

- govulncheck analyzes reachable Go vulnerabilities.
- cargo audit checks the Rust dependency graph against RustSec.
- zizmor analyzes GitHub Actions workflow definitions for risky or exploitable patterns.
- Build workflow Actions are pinned to full commit SHAs and checkout credential persistence is disabled.
- Dependabot monitors Gradle, GitHub Actions, Go Modules and Cargo.

A clean automated result means no finding blocked the tool under its database and rules at scan time; it does not prove the absence of every possible vulnerability.

## 4. Dependency locks and checksum verification

The workflow generates bootstrap artifacts for:
- Gradle lock state in gradle.lockfile and app/gradle.lockfile.
- SHA-256 artifact checksums in gradle/verification-metadata.xml.
- Cargo.lock for Rust.
- go.sum for Go modules.

These files are uploaded for review and are not automatically committed to main. Review the dependency graph and initial artifact checksums before committing them. Locking and verification establish repeatability after review; they do not prove that the first downloaded upstream source was benign.

## 5. Reproducible build check

A workflow performs two clean debug APK builds on the same runner image and toolchain and compares SHA-256. Equality is evidence of repeatability within that environment only; it is not proof across independent hosts or operating systems.

## 6. Production release signing and provenance

A manual workflow builds a signed release APK, verifies its signature, and creates a provenance attestation.

The repository owner must configure these GitHub Actions secrets:
- RELYRA_RELEASE_KEYSTORE_BASE64
- RELYRA_RELEASE_STORE_PASSWORD
- RELYRA_RELEASE_KEY_ALIAS
- RELYRA_RELEASE_KEY_PASSWORD

The keystore is never committed; it is installed temporarily on the runner with restricted permissions and deleted after the job. No signed release is produced until the owner provisions the key and secrets and runs the workflow successfully. A debug APK is not a production-signed release.

## 7. RTL and accessibility audit

- Arabic/English resource-key parity is already checked by the project validator.
- A preliminary static scan inspects icon-only Compose IconButtons and clickable images for missing spoken descriptions.
- Static findings are triage hints, not an accessibility certification. Manual TalkBack, focus order, contrast, font scaling, RTL reading order and touch-target testing remain required.

## 8. Independent security review — still pending

No independent third-party review has been completed for cryptography, key management, encrypted storage, MLS, transports, networking, Local API or plugin isolation. Automation prepares component inventories and evidence but cannot replace an independent assessor. Track every finding with severity, impact, affected version, reproduction steps, remediation and retest evidence.

## Phase 9 acceptance gates

The phase remains open until:
1. Dependency lock and checksum files are reviewed and committed.
2. Vulnerability and workflow findings are triaged and fixed or formally risk-accepted.
3. Accessibility findings are closed after manual testing.
4. Every dependency license is reviewed, including Smack Signal OMEMO.
5. Reproducibility is checked across two independent environments, not only one runner.
6. An independent security assessment is completed and critical/high findings are closed or formally accepted.
7. Release signing secrets are provisioned, the signed APK is verified, and its attestation is checked.
