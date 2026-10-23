import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, putJson } from './http';

// The login client endpoints of the API of the core (LoginClientResource in orgkeep-core): the client the users of
// every organisation log in through, to the applications of the realm owner. Its clientId is the realm name.
// Mirror the records of io.orgkeep.api.model. Written by hand for now: to be replaced by types generated
// from /q/openapi.

export interface LoginClient {
  clientId: string;
  // the "iss" of the tokens: what the APIs of the realm owner check
  issuer: string;
  // the Keycloak admin console of the realm, null when the realm owner has no access to it (SaaS)
  consoleUrl: string | null;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  webOrigins: string[];
  // the catalogs in every token of the client, the other ones only when an app asks for them (scope=<catalog>)
  defaultCatalogs: string[];
  // lightweight access tokens: the APIs of the realm owner read the roles and the catalogs by introspection
  lightweightTokens: boolean;
}

// The three lists of URIs are replaced whole
export interface UpdateLoginClientRequest {
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  webOrigins: string[];
  defaultCatalogs: string[];
  // optional: left out, the core keeps the current mode
  lightweightTokens?: boolean;
}

// The client orgkeep-api (OrgkeepApiClientResource in orgkeep-core): the login client of the screens an integrator
// builds on the API of Orgkeep. No catalog in its tokens, never lightweight: only its URIs are set here.
export interface OrgkeepApiClient {
  clientId: string;
  issuer: string;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  webOrigins: string[];
}

// The three lists are replaced whole
export interface UpdateOrgkeepApiClientRequest {
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  webOrigins: string[];
}

const ORGKEEP_API_CLIENT_KEY = 'orgkeep-api-client';
const ORGKEEP_API_CLIENT_URL = '/api/admin/orgkeep-api-client';

export function useOrgkeepApiClient() {
  return useQuery({
    queryKey: [ORGKEEP_API_CLIENT_KEY],
    queryFn: () => getJson<OrgkeepApiClient>(ORGKEEP_API_CLIENT_URL),
  });
}

export function useUpdateOrgkeepApiClient() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateOrgkeepApiClientRequest) =>
      putJson<OrgkeepApiClient>(ORGKEEP_API_CLIENT_URL, request),
    onSuccess: (saved) => queryClient.setQueryData([ORGKEEP_API_CLIENT_KEY], saved),
  });
}

const LOGIN_CLIENT_KEY = 'login-client';
const LOGIN_CLIENT_URL = '/api/admin/login-client';

export function useLoginClient() {
  return useQuery({
    queryKey: [LOGIN_CLIENT_KEY],
    queryFn: () => getJson<LoginClient>(LOGIN_CLIENT_URL),
  });
}

export function useUpdateLoginClient() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateLoginClientRequest) => putJson<LoginClient>(LOGIN_CLIENT_URL, request),
    // the answer is the client as saved (Keycloak may reorder the URIs): put it in the cache as is
    onSuccess: (saved) => queryClient.setQueryData([LOGIN_CLIENT_KEY], saved),
  });
}
