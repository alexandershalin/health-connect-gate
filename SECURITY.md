# Security policy

## Supported versions

Only the latest release receives fixes. The project is a pre-release (0.x) maintained by one person on a best-effort basis.

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability** ([direct link](https://github.com/alexandershalin/health-connect-gate/security/advisories/new)).
Do not open a public issue or pull request for a security problem.

Please include what you found, how to reproduce it and which version is affected. **Never include real health data,
tokens, passwords or your server's address** in a report; synthetic examples are enough.

What to expect (best effort): an acknowledgement within about a week, a fix or a mitigation plan as soon as the issue is
understood, and coordinated disclosure - by default no later than 90 days after the report, or earlier once a fixed release is out.

## Scope

In scope: the Android app, the reference server in `server/`, the release workflow and the published APKs.
Out of scope: vulnerabilities in third-party software (for example Hermes Agent, Android, Health Connect - report them to their
maintainers), problems caused by a misconfigured deployment (no TLS, a weak password, a publicly reachable receiver without
an authenticating service in front of it) and social engineering.

## How releases are protected

* Releases are built by GitHub Actions and signed with the project's release key; the certificate's SHA-256 fingerprint is
  published in the [README](README.md#releases). Verify it with `apksigner verify --print-certs` before installing.
* Actions are pinned to commit SHAs, the workflow token is read-only by default, secret scanning and push protection are enabled,
  and `scripts/check-secrets.sh` blocks credentials, keys and server addresses from being committed.
* The app talks only HTTPS to the server you configure; the receiver fails closed when its auth backend is unreachable.

Known limitations are listed in the README under *Limitations* (for example, tokens are stored unencrypted in the app's private storage).
