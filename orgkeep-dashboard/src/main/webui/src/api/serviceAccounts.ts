import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, postJson, putJson } from './http';
import { SERVICE_ACCOUNTS_KEY, TEAMS_KEY } from './queryKeys';

// The service accounts of an organisation, managed by its org admins and by the realm owner
// (ServiceAccountsResource in orgkeep-core). A service account is an application of the org that gets tokens
// with the client credentials grant. Its rights come from its teams, like a user.
// Mirror the records of org.orgkeep.api.model. Written by hand for now: to be replaced by types generated from /q/openapi.

export interface ServiceAccount {
  // a slug, unique in the org
  name: string;
  // what the application authenticates with, e.g. "org-acme.billing"
  clientId: string;
  // the id of the user behind the client: what a reserved catalog role is granted to
  userId: string;
  description: string | null;
  // false: no new token is issued
  enabled: boolean;
  // the catalogs in every token. The others are asked with the scope parameter
  defaultCatalogs: string[];
  // names of the teams it belongs to
  teams: string[];
}

// What the application needs to get a token. The secret is only in the answers of the creation and of a rotation:
// the core never gives it again
export interface ServiceAccountCredentials {
  clientId: string;
  clientSecret: string;
  tokenUrl: string;
}

export interface ServiceAccountCreated {
  serviceAccount: ServiceAccount;
  credentials: ServiceAccountCredentials;
}

export interface CreateServiceAccountRequest {
  name: string;
  description: string | null;
  defaultCatalogs: string[];
}

// Replaces both values
export interface UpdateServiceAccountRequest {
  description: string | null;
  defaultCatalogs: string[];
}

// Every query of this file starts with this key and the org. The teams show the service accounts too
// (api/teams.ts invalidates this key when a team changes)

function orgUrl(org: string): string {
  return `/api/organisations/${encodeURIComponent(org)}`;
}

function serviceAccountsUrl(org: string): string {
  return `${orgUrl(org)}/service-accounts`;
}

function serviceAccountUrl(org: string, name: string): string {
  return `${serviceAccountsUrl(org)}/${encodeURIComponent(name)}`;
}

export function useServiceAccounts(org: string) {
  return useQuery({
    queryKey: [SERVICE_ACCOUNTS_KEY, org, 'list'],
    queryFn: () => getJson<ServiceAccount[]>(serviceAccountsUrl(org)),
  });
}

// The catalogs where the org has a product: the only ones a service account can have by default (the core answers
// 400 otherwise). The same call as the team screens use for their roles.
export function useOrgCatalogs(org: string) {
  return useQuery({
    queryKey: [SERVICE_ACCOUNTS_KEY, org, 'catalogs'],
    queryFn: () => getJson<string[]>(`${orgUrl(org)}/catalogs`),
  });
}

// After a change: the list is read again. The teams too, they list their service accounts
function useInvalidate(org: string) {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: [TEAMS_KEY, org] });
    return queryClient.invalidateQueries({ queryKey: [SERVICE_ACCOUNTS_KEY, org] });
  };
}

// The credentials come in the answer: the caller copies them into its own state to show them once (and resets this
// mutation), they are never put in the query cache
export function useCreateServiceAccount(org: string) {
  const invalidate = useInvalidate(org);
  return useMutation({
    mutationFn: (request: CreateServiceAccountRequest) => postJson<ServiceAccountCreated>(serviceAccountsUrl(org), request),
    onSuccess: invalidate,
  });
}

export function useUpdateServiceAccount(org: string) {
  const invalidate = useInvalidate(org);
  return useMutation({
    mutationFn: ({ name, request }: { name: string; request: UpdateServiceAccountRequest }) =>
      putJson<ServiceAccount>(serviceAccountUrl(org, name), request),
    onSuccess: invalidate,
  });
}

// The old secret stops working at once. The new one is only in the answer: the caller shows it once, then resets this mutation
export function useRotateServiceAccountSecret(org: string) {
  return useMutation({
    mutationFn: (name: string) => postJson<ServiceAccountCredentials>(`${serviceAccountUrl(org, name)}/secret`, {}),
  });
}

export function useSetServiceAccountEnabled(org: string) {
  const invalidate = useInvalidate(org);
  return useMutation({
    mutationFn: ({ name, enabled }: { name: string; enabled: boolean }) =>
      putJson<ServiceAccount>(`${serviceAccountUrl(org, name)}/enabled`, { enabled }),
    onSuccess: invalidate,
  });
}

export function useDeleteServiceAccount(org: string) {
  const invalidate = useInvalidate(org);
  return useMutation({
    mutationFn: (name: string) => deleteRequest(serviceAccountUrl(org, name)),
    onSuccess: invalidate,
  });
}
