import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, postJson, putJson, putRequest } from './http';
import type { FeatureQuota, QuotaCycle } from './organisations';

// The teams of an organisation, managed by its org admins (UserGroupsResource, TeamProductsResource,
// TeamCatalogRolesResource, AvailableProductsResource and AvailableCatalogsResource in orgkeep-core).
// A team is a user group of the org: it gives products of the org and catalog roles to its members.
// Mirror the records of io.orgkeep.api.model. Written by hand for now: to be replaced by types generated from /q/openapi.

// A product given by a team
export interface TeamProduct {
  catalog: string;
  product: string;
  // the features asked for by the org admin. null: every feature the org bought, also those it buys later
  features: string[] | null;
  // what the members really get: the features asked for that the org bought
  effectiveFeatures: string[];
  // null: no quota of team, the members only use the pool of the org (always null for a metered product)
  quota: number | null;
  cycle: QuotaCycle | null;
  // metered product: the quotas of team by metered feature. A feature missing here only uses the pool of the org
  featureQuotas: Record<string, FeatureQuota>;
}

export interface TeamCatalogRole {
  catalog: string;
  role: string;
}

export interface UserGroup {
  id: string;
  name: string;
  // admin and users come with the org: they cannot be deleted
  predefined: boolean;
  memberIds: string[];
  products: TeamProduct[];
  catalogRoles: TeamCatalogRole[];
}

// A feature the org bought, as the org admin sees it
export interface AvailableFeature {
  name: string;
  displayName: string | null;
  metered: boolean;
  // metered feature: the quota of the org for it, and its cycle. null otherwise
  quota: number | null;
  cycle: QuotaCycle | null;
}

// A product of the org, as the org admin sees it: the org admin cannot read the catalogs of the realm owner,
// the display names come with it
export interface AvailableProduct {
  catalog: string;
  product: string;
  displayName: string | null;
  // true: no quota of product, a quota per metered feature, and no quota of team
  metered: boolean;
  // simple product: the pool of the org, and its cycle (the default cycle of a quota of team). null if metered
  quota: number | null;
  cycle: QuotaCycle | null;
  // the most a team can give
  boughtFeatures: AvailableFeature[];
}

// A catalog role the org admins can give through their teams (CatalogRole in the core)
export interface GrantableRole {
  catalog: string;
  name: string;
  displayName: string | null;
  description: string | null;
}

export interface GrantProductRequest {
  // null: every feature the org bought. [] : the product without any feature (refused for a metered product)
  features: string[] | null;
  // null: no quota of team (always null for a metered product)
  quota: number | null;
  // null: the cycle of the org
  cycle: QuotaCycle | null;
  // metered product only: quotas of team by metered feature given (TeamFeatureQuota in the core). Cycle null: the
  // cycle of the org for the feature
  featureQuotas: Record<string, { quota: number; cycle: QuotaCycle | null }>;
}

// Every query of this file starts with this key and the org: invalidating them refreshes the whole team screens
const TEAMS_KEY = 'teams';

// encodeURIComponent: the names come from the URL, they must not be able to change the path of the call
function orgUrl(org: string): string {
  return `/api/organisations/${encodeURIComponent(org)}`;
}

function teamUrl(org: string, team: string): string {
  return `${orgUrl(org)}/user-groups/${encodeURIComponent(team)}`;
}

export function useUserGroups(org: string) {
  return useQuery({
    queryKey: [TEAMS_KEY, org, 'groups'],
    queryFn: () => getJson<UserGroup[]>(`${orgUrl(org)}/user-groups`),
  });
}

// The products of the org: what its teams can give
export function useAvailableProducts(org: string) {
  return useQuery({
    queryKey: [TEAMS_KEY, org, 'products'],
    queryFn: () => getJson<AvailableProduct[]>(`${orgUrl(org)}/products`),
  });
}

// The catalog roles the teams can give, in every catalog where the org has a product.
// One call for the catalogs, then one per catalog, all at once: Promise.all waits for all of them,
// like CompletableFuture.allOf.
async function fetchGrantableRoles(org: string): Promise<GrantableRole[]> {
  const catalogs = await getJson<string[]>(`${orgUrl(org)}/catalogs`);
  const perCatalog = await Promise.all(
    catalogs.map((catalog) => getJson<GrantableRole[]>(`${orgUrl(org)}/catalogs/${encodeURIComponent(catalog)}/roles`)),
  );
  return perCatalog.flat();
}

export function useGrantableRoles(org: string) {
  return useQuery({
    queryKey: [TEAMS_KEY, org, 'grantableRoles'],
    queryFn: () => fetchGrantableRoles(org),
  });
}

// After any change of a team: the list of the groups is read again (products, roles, members)
function useInvalidateTeams(org: string) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: [TEAMS_KEY, org] });
}

export function useCreateUserGroup(org: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: (name: string) => postJson<UserGroup>(`${orgUrl(org)}/user-groups`, { name }),
    onSuccess: invalidate,
  });
}

export function useDeleteUserGroup(org: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: (team: string) => deleteRequest(teamUrl(org, team)),
    onSuccess: invalidate,
  });
}

// PUT on the membership: putting a member twice in a team is not an error
export function useAddTeamMember(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: (userId: string) => putRequest(`${teamUrl(org, team)}/members/${encodeURIComponent(userId)}`),
    onSuccess: invalidate,
  });
}

export function useRemoveTeamMember(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: (userId: string) => deleteRequest(`${teamUrl(org, team)}/members/${encodeURIComponent(userId)}`),
    onSuccess: invalidate,
  });
}

function teamProductUrl(org: string, team: string, catalog: string, product: string): string {
  return `${teamUrl(org, team)}/products/${encodeURIComponent(catalog)}/${encodeURIComponent(product)}`;
}

// Giving a product already given replaces what the team gives of it: the same call for "Give" and "Edit"
export function useGrantProduct(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: ({ catalog, product, request }: { catalog: string; product: string; request: GrantProductRequest }) =>
      putJson<TeamProduct>(teamProductUrl(org, team, catalog, product), request),
    onSuccess: invalidate,
  });
}

export function useRevokeProduct(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: ({ catalog, product }: { catalog: string; product: string }) =>
      deleteRequest(teamProductUrl(org, team, catalog, product)),
    onSuccess: invalidate,
  });
}

function teamRoleUrl(org: string, team: string, catalog: string, role: string): string {
  return `${teamUrl(org, team)}/catalogs/${encodeURIComponent(catalog)}/roles/${encodeURIComponent(role)}`;
}

export function useGrantCatalogRole(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: ({ catalog, role }: { catalog: string; role: string }) =>
      putRequest(teamRoleUrl(org, team, catalog, role)),
    onSuccess: invalidate,
  });
}

export function useRevokeCatalogRole(org: string, team: string) {
  const invalidate = useInvalidateTeams(org);
  return useMutation({
    mutationFn: ({ catalog, role }: { catalog: string; role: string }) =>
      deleteRequest(teamRoleUrl(org, team, catalog, role)),
    onSuccess: invalidate,
  });
}
