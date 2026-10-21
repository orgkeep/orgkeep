import { useQuery } from '@tanstack/react-query';
import { type Catalog, CATALOGS_URL, catalogUrl } from './catalogs';
import { getJson } from './http';

// Mirror the records of io.orgkeep.api.model in orgkeep-core.
// Written by hand for now: to be replaced by types generated from /q/openapi.

// A feature of a product: a permission ("read", "stats") or, if metered, a capacity an org buys with its own quota
// ("scan"). In the tokens: "<product>:scan"
export interface Feature {
  name: string;
  displayName: string | null;
  // set when the feature is created, never changed afterwards
  metered: boolean;
}

// Values of the ParamType enum of the core
export type ParamType = 'NUMBER' | 'STRING' | 'ENUM' | 'BOOLEAN';

// A parameter of a product, whose value each org negotiates (e.g. "region")
export interface ParamDefinition {
  name: string;
  type: ParamType;
  required: boolean;
  // applied by the core when the org gives no value
  defaultValue: string | null;
  // the allowed values of an enum, empty otherwise
  values: string[];
}

export interface Product {
  // the same product name may exist in several catalogs: a product is identified by both
  catalog: string;
  // e.g. "billing": the role name in resource_access.<catalog>
  name: string;
  displayName: string | null;
  description: string | null;
  features: Feature[];
  // derived by the core: true if at least one feature is metered. Then no access role nor quota of product in the
  // tokens, a quota per metered feature bought
  metered: boolean;
  params: ParamDefinition[];
  orgCount: number;
}

// One string for the pair, e.g. "prod/billing": the value of a Select, the key of a table row.
// A slug never holds a "/", so the pair can be split back without ambiguity.
export function productKey(catalog: string, name: string): string {
  return `${catalog}/${name}`;
}

export function splitProductKey(key: string): { catalog: string; name: string } {
  const [catalog, name] = key.split('/');
  return { catalog, name };
}

// The core lists the products of one catalog, the screens want them all: one call for the catalogs, then one per
// catalog, all at once (a realm has a handful). Promise.all waits for all of them, like CompletableFuture.allOf.
async function fetchAllProducts(): Promise<Product[]> {
  const catalogs = await getJson<Catalog[]>(CATALOGS_URL);
  const perCatalog = await Promise.all(
    catalogs.map((catalog) => getJson<Product[]>(`${catalogUrl(catalog.name)}/products`)),
  );
  // the catalogs come sorted by name, the products of each one too
  return perCatalog.flat();
}

// Every product of every catalog, sorted by catalog then product
export function useProducts() {
  return useQuery({
    queryKey: ['products'],
    queryFn: fetchAllProducts,
  });
}

// The products grouped by catalog, in the shape of a Mantine Select with groups:
// [{ group: 'prod', items: [{ value: 'prod/billing', label: 'Billing' }] }]
export function productOptions(products: Product[]): { group: string; items: { value: string; label: string }[] }[] {
  const groups: { group: string; items: { value: string; label: string }[] }[] = [];
  for (const product of products) {
    let group = groups.find((g) => g.group === product.catalog);
    if (!group) {
      group = { group: product.catalog, items: [] };
      groups.push(group);
    }
    group.items.push({ value: productKey(product.catalog, product.name), label: product.displayName ?? product.name });
  }
  return groups;
}
