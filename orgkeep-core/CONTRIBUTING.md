# Contributing to Orgkeep

Thank you for your interest in Orgkeep. It is a personal project, maintained in spare time: a review can take a few
days.

## Before you start

- **Small changes** (a typo, the docs, an obvious bug): open a pull request directly.
- **Larger changes** (a new feature, a change in the API or in what is written in Keycloak): open an issue first, to
  agree on the approach before you spend time on it.
- **Security vulnerabilities**: never in a public issue. Use *Report a vulnerability* in the *Security* tab of the
  repository, which keeps the report private (see [SECURITY.md](SECURITY.md)).

## Sign off your commits (DCO)

Orgkeep uses the [Developer Certificate of Origin](https://developercertificate.org) (DCO) instead of a contributor
license agreement. By signing off a commit, you certify that you wrote it, or that you have the right to submit it
under the license of the project.

Sign off each commit with `-s`:

```
git commit -s -m "Fix the quota cycle of a team"
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

- One change per pull request, on a branch created from `main`; bring `main` in with a rebase, not a merge. A pull
  request is squashed into one commit, unless its commits are clean and each makes sense on its own: they are then
  merged as they are.
- A change of behaviour comes with tests, and `mvn test` passes (see the [README](README.md) to build and run). The
  *Build* check runs them again on each pull request.
- Follow the style of the surrounding code. Comments are in English.
- A commit message starts with a short summary line, followed by why the change is needed when it is not obvious.
- No IDE files or build output in the commits.

## Where things go

The packages are under `org.orgkeep`:

- `api`: the contract shared with other modules (service interfaces, request and response records, events). It
  depends on nothing else in the core.
- `components`: the features, nested like the model (a product lives in its catalog, a member in its organisation),
  each with its REST resource, its service and its mapper.
- `repository/keycloak`: the only package that uses the Keycloak admin client.

Code style of the project: Lombok `@Slf4j` for logging, constructor injection with `@RequiredArgsConstructor` and
`private final` fields, and the CDI scopes kept strict (no request-scoped bean injected into an application-scoped
one).

The tests start their own Keycloak with Quarkus Dev Services and work in throwaway realms: Docker must be running.
