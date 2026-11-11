import type { OrgRole } from '../api/organisations';

// The colors of the role badges of the members: owner > admin > user. Shared by the detail of an organisation
// (realm owner) and the members page of the org admins.
export const ROLE_COLORS: Record<OrgRole, string> = {
  ORG_OWNER: 'orange',
  ORG_ADMIN: 'blue',
  ORG_USER: 'gray',
};
