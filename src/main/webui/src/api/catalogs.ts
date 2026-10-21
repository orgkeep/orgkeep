import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, postJson, putJson } from './http';
import type { ParamDefinition, Product, ProductRole } from './products';

// The catalogs endpoints of the API of the core (CatalogsResource and ProductsResource in orgkeep-core),
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
  roles: ProductRole[];
  params: ParamDefinition[];
}

// The name cannot change, the roles are changed one by one (see usePutProductRole)
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

// ---- Roles of a product ----

// Creates the role, or changes its display name: the core has a single PUT for both
export function usePutProductRole(catalog: string, product: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ role, displayName }: { role: string; displayName: string | null }) =>
      putJson<Product>(`${productUrl(catalog, product)}/roles/${encodeURIComponent(role)}`, { displayName }),
    onSuccess: () => refreshCatalogs(queryClient),
  });
}

// The core also removes the role from the bought roles of the organisations
export function useDeleteProductRole(catalog: string, product: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (role: string) => deleteRequest(`${productUrl(catalog, product)}/roles/${encodeURIComponent(role)}`),
    onSuccess: () => {
      // the bought roles of the organisations changed too
      queryClient.invalidateQueries({ queryKey: ['organisations'] });
      return refreshCatalogs(queryClient);
    },
  });
}
