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

- The project has not yet received an independent cryptographic security audit.
- The current transport is Nostr over user-configured WSS relays; WSS does not hide client IP addresses or all traffic metadata.
- Group encryption currently uses separate one-to-one sessions per member; it is not MLS.
- Planned protocols and transports must not be described as security controls until implemented and tested.
