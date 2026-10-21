import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, postJson, putJson, putRequest } from './http';
import type { Feature, ParamDefinition, Product } from './products';

// The catalogs endpoints of the API of the core (CatalogsResource, ProductsResource and CatalogRolesResource in orgkeep-core),
// called directly by the front. Mirror the records of io.orgkeep.api.model.
// Written by hand for now: to be replaced by types generated from /q/openapi.

export interface Catalog {
  // the clientId of the catalog client, also the name of its claim in the tokens
  name: string;
  displayName: string | null;
  description: string | null;
  productCount: number;
}

export interface CreateCatalogRequest {
  name: string;
  displayName: string;
  description: string | null;
}

// the name of a catalog cannot change
export interface UpdateCatalogRequest {
  displayName: string;
  description: string | null;
}

export interface CreateProductRequest {
  name: string;
  displayName: string;
  description: string | null;
  features: Feature[];
  params: ParamDefinition[];
}

// The name cannot change, the features are changed one by one (see usePutFeature)
export interface UpdateProductRequest {
  displayName: string;
  description: string | null;
  // the whole schema: a parameter left out is removed
  params: ParamDefinition[];
}

// Every query of this file starts with this key: invalidating it refreshes all of them at once.
const CATALOGS_KEY = 'catalogs';

export const CATALOGS_URL = '/api/admin/catalogs';

// encodeURIComponent: the names come from the URL, they must not be able to change the path of the call
export function catalogUrl(catalog: string): string {
  return `${CATALOGS_URL}/${encodeURIComponent(catalog)}`;
}

function productUrl(catalog: string, product: string): string {
  return `${catalogUrl(catalog)}/products/${encodeURIComponent(product)}`;
}

// A change on a catalog or a product also changes what the other screens show: the product filter of the
// organisations and the products of an organisation read ['products'] (api/products.ts).
function refreshCatalogs(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['products'] });
  return queryClient.invalidateQueries({ queryKey: [CATALOGS_KEY] });
}

// ---- Catalogs ----

export function useCatalogs() {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'list'],
    queryFn: () => getJson<Catalog[]>(CATALOGS_URL),
  });
}

export function useCatalog(catalog: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog],
    queryFn: () => getJson<Catalog>(catalogUrl(catalog)),
  });
}

export function useCreateCatalog() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateCatalogRequest) => postJson<Catalog>(CATALOGS_URL, request),
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

export function useUpdateCatalog(catalog: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateCatalogRequest) => putJson<Catalog>(catalogUrl(catalog), request),
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

export function useDeleteCatalog() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (catalog: string) => deleteRequest(catalogUrl(catalog)),
    // removeQueries, not invalidate: the deleted catalog and its products must not be fetched again (404).
    // The key is a prefix: it removes the products of the catalog too.
    onSuccess: (_result, catalog) => {
      queryClient.removeQueries({ queryKey: [CATALOGS_KEY, 'detail', catalog] });
      return refreshCatalogs(queryClient);
    },
  });
}

// ---- Products of a catalog ----

export function useCatalogProducts(catalog: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog, 'products'],
    queryFn: () => getJson<Product[]>(`${catalogUrl(catalog)}/products`),
  });
}

export function useProduct(catalog: string, product: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog, 'products', product],
    queryFn: () => getJson<Product>(productUrl(catalog, product)),
  });
}

export function useCreateProduct(catalog: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateProductRequest) => postJson<Product>(`${catalogUrl(catalog)}/products`, request),
    // the catalog counts its products: the whole CATALOGS_KEY is refreshed
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

export function useUpdateProduct(catalog: string, product: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateProductRequest) => putJson<Product>(productUrl(catalog, product), request),
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

export function useDeleteProduct(catalog: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (product: string) => deleteRequest(productUrl(catalog, product)),
    onSuccess: (_result, product) => {
      queryClient.removeQueries({ queryKey: [CATALOGS_KEY, 'detail', catalog, 'products', product] });
      return refreshCatalogs(queryClient);
    },
  });
}

// ---- Catalog roles (CatalogRolesResource) ----

// A role valid for the whole catalog (scanner, beta-tester...), in resource_access.<catalog>.roles of the tokens
export interface CatalogRole {
  catalog: string;
  name: string;
  displayName: string | null;
  description: string | null;
  // true: the org admins give it through their teams. False: reserved, only the realm owner gives it, to a user
  orgGrantable: boolean;
}

export interface CreateCatalogRoleRequest {
  name: string;
  displayName: string | null;
  description: string | null;
  orgGrantable: boolean;
}

// the name of a role cannot change
export interface UpdateCatalogRoleRequest {
  displayName: string | null;
  description: string | null;
  orgGrantable: boolean;
}

// A user holding the role directly (given by the realm owner), not through a team
export interface RoleHolder {
  userId: string;
  username: string;
  email: string | null;
}

function rolesUrl(catalog: string): string {
  return `${catalogUrl(catalog)}/roles`;
}

function roleUrl(catalog: string, role: string): string {
  return `${rolesUrl(catalog)}/${encodeURIComponent(role)}`;
}

// A change of a role also changes the teams: a role made reserved leaves them, a deleted one too
function refreshRoles(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['teams'] });
  return refreshCatalogs(queryClient);
}

export function useCatalogRoles(catalog: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles'],
    queryFn: () => getJson<CatalogRole[]>(rolesUrl(catalog)),
  });
}

export function useCatalogRole(catalog: string, role: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles', role],
    queryFn: () => getJson<CatalogRole>(roleUrl(catalog, role)),
  });
}

export function useCreateCatalogRole(catalog: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateCatalogRoleRequest) => postJson<CatalogRole>(rolesUrl(catalog), request),
    onSuccess: () => refreshRoles(queryClient),
  });
}

export function useUpdateCatalogRole(catalog: string, role: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateCatalogRoleRequest) => putJson<CatalogRole>(roleUrl(catalog, role), request),
    onSuccess: () => refreshRoles(queryClient),
  });
}

export function useDeleteCatalogRole(catalog: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (role: string) => deleteRequest(roleUrl(catalog, role)),
    onSuccess: (_result, role) => {
      queryClient.removeQueries({ queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles', role] });
      return refreshRoles(queryClient);
    },
  });
}

export function useRoleHolders(catalog: string, role: string) {
  return useQuery({
    queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles', role, 'users'],
    queryFn: () => getJson<RoleHolder[]>(`${roleUrl(catalog, role)}/users`),
  });
}

// Gives the role directly to a user, who must be a member of an organisation (409 otherwise). Idempotent
export function useGrantRoleToUser(catalog: string, role: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => putRequest(`${roleUrl(catalog, role)}/users/${encodeURIComponent(userId)}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles', role] }),
  });
}

export function useRevokeRoleFromUser(catalog: string, role: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => deleteRequest(`${roleUrl(catalog, role)}/users/${encodeURIComponent(userId)}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [CATALOGS_KEY, 'detail', catalog, 'roles', role] }),
  });
}

// ---- Features of a product ----

function featureUrl(catalog: string, product: string, feature: string): string {
  return `${productUrl(catalog, product)}/features/${encodeURIComponent(feature)}`;
}

// Creates the feature, or changes its display name: the core has a single PUT for both.
// metered is only read when the feature is created: the core answers 409 if it differs from an existing feature
export function usePutFeature(catalog: string, product: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ feature, displayName, metered }: { feature: string; displayName: string | null; metered: boolean }) =>
      putJson<Product>(featureUrl(catalog, product, feature), { displayName, metered }),
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

// The core also removes the feature from what the organisations bought. A metered feature bought by an
// organisation is refused (409): it carries a negotiated quota
export function useDeleteFeature(catalog: string, product: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (feature: string) => deleteRequest(featureUrl(catalog, product, feature)),
    onSuccess: () => {
      // the bought features of the organisations changed too
      queryClient.invalidateQueries({ queryKey: ['organisations'] });
      return refreshCatalogs(queryClient);
    },
  });
}
