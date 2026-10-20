import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, postJson, putJson } from './http';

// Mirror the records of io.orgkeep.dashboard.organisation on the Java side.
// Written by hand for now: to be replaced by types generated from /q/openapi.
export interface OrgOwner {
  id: string;
  username: string;
  email: string | null;
  // null when the owner was created without a name
  firstName: string | null;
  lastName: string | null;
}

export interface OrgSummary {
  id: string;
  name: string;
  emailDomains: string[];
  enabled: boolean;
  // sorted by username, empty when the org has no owner yet
  owners: OrgOwner[];
  // owners included
  memberCount: number;
  // the assigned products as "catalog/product", sorted
  products: string[];
}

export interface CreateOrgRequest {
  name: string;
  emailDomains: string[];
  // all three optional: null when the organisation is created without an owner
  ownerEmail: string | null;
  ownerFirstName: string | null;
  ownerLastName: string | null;
}

export interface CreateOrgResponse {
  id: string;
  name: string;
  emailDomains: string[];
  ownerId: string | null;
}

// Every query of this file starts with this key: invalidating it refreshes all of them at once.
const ORGANISATIONS_KEY = 'organisations';

// Every filter is optional: '' means no filter
export interface OrganisationFilters {
  search: string;
  // catalog and product: keeps the organisations this product of this catalog is assigned to
  catalog: string;
  product: string;
}

function fetchOrganisations({ search, catalog, product }: OrganisationFilters): Promise<OrgSummary[]> {
  // URLSearchParams escapes the values, like @QueryParam on the Java side. Empty filters are left out.
  const params = new URLSearchParams();
  if (search) {
    params.set('search', search);
  }
  if (catalog) {
    params.set('catalog', catalog);
  }
  if (product) {
    params.set('product', product);
  }
  const query = params.size > 0 ? `?${params}` : '';
  return getJson(`/api/organisations${query}`);
}

export function useOrganisations(filters: OrganisationFilters) {
  return useQuery({
    // one cache entry per combination of filters: going back to a previous one shows its result at once
    // 'list' keeps these keys apart from those of one organisation below (['organisations', 'detail', org])
    queryKey: [ORGANISATIONS_KEY, 'list', filters.search, filters.catalog, filters.product],
    queryFn: () => fetchOrganisations(filters),
    // while the result of a new search loads, keep showing the previous one instead of an empty table
    placeholderData: keepPreviousData,
  });
}

// A query reads, a mutation writes. useMutation gives mutate() and the pending / error states of the call.
export function useCreateOrganisation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateOrgRequest) => postJson<CreateOrgResponse>('/api/organisations', request),
    // the cached lists no longer match the server: mark them stale, TanStack Query fetches them again
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [ORGANISATIONS_KEY] }),
  });
}

// ---- Detail page of one organisation ----

export interface OrgDetails {
  id: string;
  name: string;
  emailDomains: string[];
  enabled: boolean;
  // the client holding the org roles, e.g. "org-acme"
  clientId: string;
  owners: OrgOwner[];
}

// Values of the OrgRole enum of the core, as Jackson writes them
export type OrgRole = 'ORG_OWNER' | 'ORG_ADMIN' | 'ORG_USER';

export interface OrgMember {
  id: string;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  enabled: boolean | null;
  role: OrgRole;
}

// Values of the QuotaCycle enum of the core
export type QuotaCycle = 'DAILY' | 'MONTHLY' | 'YEARLY';

export interface OrgProduct {
  // the display name and the roles are in the catalogs (api/products.ts)
  catalog: string;
  product: string;
  // null only if the group was changed outside of Orgkeep
  quota: number | null;
  cycle: QuotaCycle | null;
  // roles of the product the org bought, sorted
  boughtRoles: string[];
  // values of the parameters, defaults applied by the core
  params: Record<string, string>;
}

export interface AssignProductRequest {
  quota: number;
  cycle: QuotaCycle;
  boughtRoles: string[];
  // a parameter left out gets its default in the core
  params: Record<string, string>;
}

// encodeURIComponent: the name comes from the URL, it must not be able to change the path of the call
function orgUrl(org: string): string {
  return `/api/organisations/${encodeURIComponent(org)}`;
}

// The keys start with ORGANISATIONS_KEY: a change on an organisation refreshes the list too
// (owner, member count, product count), with a single invalidateQueries
export function useOrganisation(org: string) {
  return useQuery({
    queryKey: [ORGANISATIONS_KEY, 'detail', org],
    queryFn: () => getJson<OrgDetails>(orgUrl(org)),
  });
}

export function useOrgMembers(org: string) {
  return useQuery({
    queryKey: [ORGANISATIONS_KEY, 'detail', org, 'members'],
    queryFn: () => getJson<OrgMember[]>(`${orgUrl(org)}/members`),
  });
}

export function useOrgProducts(org: string) {
  return useQuery({
    queryKey: [ORGANISATIONS_KEY, 'detail', org, 'products'],
    queryFn: () => getJson<OrgProduct[]>(`${orgUrl(org)}/products`),
  });
}

export function useDeleteOrganisation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (org: string) => deleteRequest(orgUrl(org)),
    // removeQueries, not invalidate: the deleted org must not be fetched again (it would answer 404)
    onSuccess: (_result, org) => {
      queryClient.removeQueries({ queryKey: [ORGANISATIONS_KEY, 'detail', org] });
      return queryClient.invalidateQueries({ queryKey: [ORGANISATIONS_KEY] });
    },
  });
}

// The URL of the assignment of a product of a catalog to the organisation
function assignmentUrl(org: string, catalog: string, product: string): string {
  return `${orgUrl(org)}/catalogs/${encodeURIComponent(catalog)}/products/${encodeURIComponent(product)}`;
}

// Assigning a product already assigned replaces what the org negotiated: the same call for "Assign" and "Edit"
export function useAssignProduct(org: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ catalog, product, request }: { catalog: string; product: string; request: AssignProductRequest }) =>
      putJson<OrgProduct>(assignmentUrl(org, catalog, product), request),
    // the product list counts its organisations (orgCount): refresh it too
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      return queryClient.invalidateQueries({ queryKey: [ORGANISATIONS_KEY] });
    },
  });
}

export function useUnassignProduct(org: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ catalog, product }: { catalog: string; product: string }) =>
      deleteRequest(assignmentUrl(org, catalog, product)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      return queryClient.invalidateQueries({ queryKey: [ORGANISATIONS_KEY] });
    },
  });
}
