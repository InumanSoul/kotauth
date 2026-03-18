# Security Policy

## Supported Versions

Only the latest released version of Kotauth receives security fixes.

| Version | Supported |
|---------|-----------|
| 1.x     | ✅ Yes    |
| < 1.0   | ❌ No     |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

If you believe you have found a security vulnerability in Kotauth, report it via GitHub's private vulnerability reporting:

**[Report a vulnerability](https://github.com/InumanSoul/kotauth/security/advisories/new)**

Alternatively, email **inumansoul@gmail.com** with the subject line `[SECURITY] Kotauth vulnerability`.

Please include:

- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- The affected version(s)
- Any suggested mitigations if you have them

## What to Expect

- **Acknowledgement** within 48 hours confirming receipt
- **Initial assessment** within 5 business days
- **Fix and disclosure timeline** agreed upon collaboratively — typically 90 days from report, sooner for critical issues

We follow responsible disclosure. We will credit reporters in the release notes unless you prefer to remain anonymous.

## Scope

Issues considered in scope:

- Authentication or authorization bypass
- Token forgery or replay attacks
- Session fixation or hijacking
- Information disclosure (user data, tenant data, credentials)
- Injection vulnerabilities (SQL, header, log)
- Cryptographic weaknesses in JWT signing, password hashing, or secret storage

Out of scope:

- Vulnerabilities in dependencies not yet fixed upstream (report to the dependency maintainer)
- Issues requiring physical access to the host machine
- Social engineering attacks
- Self-XSS with no meaningful impact

## Security Design Notes

For context on Kotauth's security model, see the architecture section in [README.md](README.md) and the ADR table in [docs/ROADMAP.md](docs/ROADMAP.md).
