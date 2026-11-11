# Contributing to RELYRA

Thanks for considering a contribution.

## Before opening a pull request

- Check whether the feature is already present in the source and read `docs/SPECIFICATION_CROSSWALK_AR.md`.
- Keep changes focused. Prefer a small package with a clear contract over expanding unrelated classes.
- Do not add placeholder screens, fake connection states, demo messages or buttons without a real operation behind them.
- Describe Android permissions, background-service and battery implications.
- State what data is exposed to relays, peers and file servers.

## Validation

Run from the repository root:

```bash
python3 scripts/verify_project.py
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
```

Add tests for normal operation, failure, retry, malformed inputs and state recovery. Network features should also include integration tests against a controllable endpoint and device tests on Wi-Fi and mobile data.

## Translations and accessibility

- Add user-facing strings to both `values/strings.xml` and `values-ar/strings.xml`.
- Check Arabic RTL layout and run `scripts/verify_project.py`.
- Provide useful labels for interactive icons and controls.
- Do not use color alone to communicate a status.

## Security-sensitive changes

Do not invent cryptographic primitives. Prefer reviewed libraries and protocols. Explain key lifecycle, replay handling, authentication, metadata exposure and recovery behaviour. Never log message contents, private keys, recovery material or contact identifiers in diagnostic reports.
