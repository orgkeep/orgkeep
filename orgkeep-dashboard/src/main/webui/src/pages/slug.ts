// The naming rule of orgkeep-core for every identifier (organisation, catalog, product, role, parameter):
// lowercase letters, digits and inner hyphens. Checked in the forms so the user sees the error while typing,
// but the core stays the reference: its 400 would be shown anyway.
export const SLUG_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;

export function isSlug(value: string): boolean {
  return SLUG_PATTERN.test(value.trim());
}
