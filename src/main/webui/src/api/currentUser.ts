import { useQuery } from '@tanstack/react-query';

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

async function fetchCurrentUser(): Promise<CurrentUser> {
  // the session cookie is sent automatically: same origin
  const response = await fetch('/api/me');
  if (!response.ok) {
    throw new Error(`GET /api/me failed with status ${response.status}`);
  }
  return response.json();
}

export function useCurrentUser() {
  return useQuery({
    queryKey: ['current-user'],
    queryFn: fetchCurrentUser,
    // the user does not change while the page is open: login and logout reload the whole page
    staleTime: Infinity,
  });
}
