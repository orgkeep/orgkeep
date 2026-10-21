import { useQuery } from '@tanstack/react-query';
import { getJson } from './http';

// Mirrors CurrentUserResource.CurrentUser on the Java side.
// Written by hand for now: to be replaced by types generated from /q/openapi.
export interface CurrentUser {
  authenticated: boolean;
  // null when not authenticated
  username: string | null;
  fullName: string | null;
  email: string | null;
  realmRoles: string[];
  // org name -> roles in that org, e.g. { acme: ['org-admin', 'org-owner', 'org-user'] }
  orgRoles: Record<string, string[]>;
}

export function useCurrentUser() {
  return useQuery({
    queryKey: ['current-user'],
    queryFn: () => getJson<CurrentUser>('/api/me'),
    // the user does not change while the page is open: login and logout reload the whole page
    staleTime: Infinity,
  });
}
