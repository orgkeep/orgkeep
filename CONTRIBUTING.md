# Contributing to Orgkeep

Thank you for your interest in Orgkeep. It is a personal project, maintained in spare time: a review can take a few
days.

## Before you start

- **Small changes** (a typo, the docs, an obvious bug): open a pull request directly.
- **Larger changes** (a new screen, a change in how the dashboard works): open an issue first, to agree on the approach
  before you spend time on it.
- **Security vulnerabilities**: never in a public issue. Use *Report a vulnerability* in the *Security* tab of the
  repository, which keeps the report private (see [SECURITY.md](SECURITY.md)).

## Sign off your commits (DCO)

Orgkeep uses the [Developer Certificate of Origin](https://developercertificate.org) (DCO) instead of a contributor
license agreement. By signing off a commit, you certify that you wrote it, or that you have the right to submit it
under the license of the project.

Sign off each commit with `-s`:

```
git commit -s -m "Show the quota cycle of a team"
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
- A change of behaviour comes with tests, and `mvn test` passes (see the [README](README.md) to build and run). The
  *Build* check runs them again on each pull request, against the `main` branch of orgkeep-core: a change that needs
  a change in the core waits for the core first.
- Follow the style of the surrounding code. Comments are in English.
- A commit message starts with a short summary line, followed by why the change is needed when it is not obvious.
- No IDE files or build output in the commits (`node_modules`, `dist`, `.quinoa`).

## Where things go

**The dashboard is only a front.** It embeds [orgkeep-core](https://github.com/orgkeep/orgkeep-core) and always goes
through its REST API, with the session of the user: it never calls Keycloak and holds no business rule. A missing rule
or check belongs in the core, not here.

- `src/main/webui`: the front, React and TypeScript with Vite, Mantine and TanStack Query. The code is meant to stay
  simple and readable: explicit rather than clever.
- `src/main/java`: only the authentication of the browser (the session and the CSRF protection).

The dev mode and the tests need the core in the local Maven repository (`mvn install` in orgkeep-core) and Docker
running (Quarkus Dev Services start Keycloak).
