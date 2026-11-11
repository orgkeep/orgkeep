# Security policy

## Report a vulnerability

Never in a public issue or pull request. Use
[*Report a vulnerability*](https://github.com/orgkeep/orgkeep-dashboard/security/advisories/new), in the *Security*
tab of the repository: the report stays private between you and the maintainer.

The dashboard only holds the authentication of the browser (the session and the CSRF protection): every check of the
rights is in [orgkeep-core](https://github.com/orgkeep/orgkeep-core). A vulnerability in the REST API goes to the
*Security* tab of the core. When in doubt, report it here.

Please include:

- the version or commit of Orgkeep, and of Keycloak;
- the steps to reproduce, or a proof of concept;
- what an attacker can do with it.

## What to expect

Orgkeep is a personal project, maintained in spare time, without a bug bounty.

- An acknowledgement within a week.
- A fix in the next release, sooner for a serious vulnerability. You are kept informed in the private advisory, where
  you can also take part in the fix.
- Once the fix is released, the advisory is published, with a CVE when it applies, and with credit to you if you wish.

Please give us the time to release a fix before you disclose the vulnerability.

## Supported versions

Orgkeep is before its 1.0: only the latest release receives security fixes.

## Out of scope

- Vulnerabilities in Keycloak itself: report them to the [Keycloak project](https://github.com/keycloak/keycloak/security/policy).
- The demo stack of [orgkeep-examples](https://github.com/orgkeep/orgkeep-examples) is not made for production: its
  known passwords and its HTTP without TLS are not vulnerabilities.
