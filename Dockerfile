# Keycloak with the orgkeep theme, used by the demo compose (orgkeep-examples):
#   docker build -t ghcr.io/orgkeep/orgkeep-keycloak:1.0.0-SNAPSHOT .
#
# The theme folder is copied in themes/, which needs no kc.sh build (unlike the jar in providers/): the image runs
# with any Keycloak option (database, hostname...), like the official one. Only the theme is added.
# The theme is not applied to anything here: Orgkeep sets it on the dashboard client (ORGKEEP_DASHBOARD_LOGIN_THEME).
ARG KEYCLOAK_VERSION=26.6.1
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --chown=1000:0 src/main/resources/theme/orgkeep /opt/keycloak/themes/orgkeep
