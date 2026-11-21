# Keycloak with the orgkeep theme, used by the demo compose (orgkeep-examples):
#   docker build -t ghcr.io/orgkeep/orgkeep-keycloak:$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) .
#
# The theme folder is copied in themes/, which needs no kc.sh build (unlike the jar in providers/): the image runs
# with any Keycloak option (database, hostname...), like the official one. Only the theme is added.
# The theme is not applied to anything here: Orgkeep sets it on the dashboard client (ORGKEEP_DASHBOARD_LOGIN_THEME).
# Default kept in sync by hand with keycloak.version of the parent pom, the single source of truth: CI and the
# release workflow pass it with --build-arg so a mismatch here is only a problem for a manual build.
ARG KEYCLOAK_VERSION=26.8.1
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
# OCI labels: GitHub links the package to its repository (and gives it the repository's access rights, not its visibility).
# title, url and documentation replace the ones inherited from the Keycloak image (version stays Keycloak's)
LABEL org.opencontainers.image.source=https://github.com/orgkeep/orgkeep \
      org.opencontainers.image.title=orgkeep-keycloak \
      org.opencontainers.image.description="Keycloak with the Orgkeep theme" \
      org.opencontainers.image.url=https://orgkeep.org \
      org.opencontainers.image.documentation=https://github.com/orgkeep/orgkeep/tree/main/orgkeep-theme \
      org.opencontainers.image.licenses=Apache-2.0
COPY --chown=1000:0 src/main/resources/theme/orgkeep /opt/keycloak/themes/orgkeep
