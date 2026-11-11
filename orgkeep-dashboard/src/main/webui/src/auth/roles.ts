import type { CurrentUser } from '../api/currentUser';

// Role names, as defined by orgkeep-core (RealmRole and OrgRole enums).
const REALM_OWNER = 'realm-owner';
const ORG_ADMIN = 'org-admin';

// These checks only decide what the front shows. The real access control stays in the backend,
// which checks the roles on every call: hiding a link is not a security measure.

export function isRealmOwner(user: CurrentUser): boolean {
  return user.realmRoles.includes(REALM_OWNER);
}

// The orgs the user administers. Keycloak gives the effective roles, so an org owner has org-admin too.
export function administeredOrgs(user: CurrentUser): string[] {
  return Object.keys(user.orgRoles).filter((org) => user.orgRoles[org].includes(ORG_ADMIN));
}
