# Security policy

RELYRA handles identities, private messages and encrypted files. Security reports should not be posted publicly before maintainers have had a chance to assess them.

## Reporting

Use GitHub's private vulnerability reporting / Security Advisories for this repository when the feature is available. Otherwise, contact the repository maintainer through a private GitHub channel. Do not include private keys, recovery phrases or real message contents in a report.

Include:
- affected commit or release;
- Android version and device model;
- steps to reproduce and expected impact;
- logs with personal identifiers, addresses, tokens and message contents removed;
- whether the issue is reproducible on a clean installation.

## Scope notes

- The project has not yet received an independent cryptographic/security audit. Phase 9 automation produces SBOM, dependency, workflow and static accessibility evidence; it is not a substitute for an independent assessor.
- The app contains legacy pairwise-encrypted groups and a separate OpenMLS-based MLS group path. Do not conflate the two implementations or claim full multi-admin RFC 9420 proposal coordination is complete.
- User-configured Nostr WSS relays do not hide client IP addresses or all traffic metadata.
- The Smack Signal OMEMO provider is identified as GPL-3.0 in the Gradle dependency declaration and still requires a documented distribution-license review.
- Debug APKs are not production releases. Release signing uses owner-managed secrets outside the repository and must be verified before distribution.
- Planned protocols and transports must not be described as security controls until implemented and tested.
