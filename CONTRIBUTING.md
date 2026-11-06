# Contributing to Orgkeep

Thank you for your interest in Orgkeep. It is a personal project, maintained in spare time: a review can take a few
days.

## Before you start

- **Small changes** (a typo, a style fix, a missing translation): open a pull request directly.
- **Larger changes** (a new page template, a new language, a change of look): open an issue first, to agree on the
  approach before you spend time on it.
- **Security vulnerabilities**: never in a public issue. Use *Report a vulnerability* in the *Security* tab of the
  repository, which keeps the report private.

## Sign off your commits (DCO)

Orgkeep uses the [Developer Certificate of Origin](https://developercertificate.org) (DCO) instead of a contributor
license agreement. By signing off a commit, you certify that you wrote it, or that you have the right to submit it
under the license of the project.

Sign off each commit with `-s`:

```
git commit -s -m "Fix the button colour of the password reset email"
```

Git adds a line with the name and email of your Git configuration, which must match the author of the commit:

```
Signed-off-by: Jane Doe <jane@example.com>
```

A check fails on the pull request if a commit is not signed off. To fix the last commit, `git commit --amend -s`; for
all the commits of your branch, `git rebase --signoff main`, then push your branch again with `--force-with-lease`.

Contributions are licensed under the [Apache License 2.0](LICENSE), like the rest of the project (section 5 of the
license).

## Pull requests

- One change per pull request, on a branch created from `main`. Pull requests are squash merged.
- Check your change in a browser, and in Mailpit for an email (see *Work on it* in the [README](README.md)). A
  screenshot in the pull request helps the review.
- Comments are in English. A commit message starts with a short summary line, followed by why the change is needed
  when it is not obvious.
- No IDE files or build output in the commits.

## Where things go

Everything is under `src/main/resources/theme/orgkeep`, one folder per type of theme: `login`, `email`, `account`.

The theme inherits from the default themes of Keycloak and only changes their appearance: CSS rather than copied
templates, nothing specific to Orgkeep in the flows. The less is copied, the less there is to update when Keycloak
changes version. Texts go in the `messages` files, with keys prefixed `orgkeep` so that they never replace a key of
Keycloak.
