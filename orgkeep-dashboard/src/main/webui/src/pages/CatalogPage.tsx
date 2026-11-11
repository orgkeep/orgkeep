import {
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import {
  type Catalog,
  useCatalog,
  useCatalogProducts,
  useCatalogRoles,
  useDeleteCatalog,
  useRegenerateSecret,
} from '../api/catalogs';
import ConfirmModal from '../components/ConfirmModal';
import CopyValue from '../components/CopyValue';
import CatalogModal from './CatalogModal';
import CatalogRoleModal from './CatalogRoleModal';
import ProductModal from './ProductModal';

// One catalog (realm owner): its header, its products and its roles.
export default function CatalogPage() {
  const { t } = useTranslation();
  // the :catalog part of the route /catalogs/:catalog. Always set on this route, hence the "!"
  const name = useParams().catalog!;
  const { data: catalog, isPending, error } = useCatalog(name);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    // e.g. 404 for an unknown catalog, typed in the URL
    return (
      <Alert color="red" title={t('catalogDetail.loadError', { catalog: name })} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack maw={1100}>
      <Breadcrumbs>
        <Anchor component={Link} to="/catalogs">
          {t('catalogDetail.breadcrumb')}
        </Anchor>
        <Text ff="monospace">{catalog.name}</Text>
      </Breadcrumbs>

      <CatalogHeader catalog={catalog} />
      <ProductsSection catalog={catalog.name} />
      <RolesSection catalog={catalog.name} />
      <TokenHint catalog={catalog.name} />
      <IntrospectionSection catalog={catalog.name} />
    </Stack>
  );
}

// Display name, name, description, and the edit and delete buttons
function CatalogHeader({ catalog }: { catalog: Catalog }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const deleteCatalog = useDeleteCatalog();
  const [editOpened, { open: openEdit, close: closeEdit }] = useDisclosure();
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();

  function closeDeleteConfirm() {
    deleteCatalog.reset();
    closeConfirm();
  }

  function confirmDelete() {
    // the page of a deleted catalog would answer 404: back to the list once the core confirmed
    deleteCatalog.mutate(catalog.name, { onSuccess: () => navigate('/catalogs') });
  }

  return (
    <Group justify="space-between" align="flex-start">
      <Stack gap={4}>
        <Group gap="sm" align="baseline">
          <Title order={2}>{catalog.displayName ?? catalog.name}</Title>
          <Code>{catalog.name}</Code>
        </Group>
        {catalog.description && <Text c="dimmed">{catalog.description}</Text>}
      </Stack>

      <Group>
        <Button variant="default" onClick={openEdit}>
          {t('common.edit')}
        </Button>
        <Button color="red" variant="outline" onClick={openConfirm}>
          {t('common.delete')}
        </Button>
      </Group>

      {/* key: the form reads its initial values once, a changed catalog gives a new form */}
      <CatalogModal
        key={`${catalog.displayName}|${catalog.description}`}
        opened={editOpened}
        onClose={closeEdit}
        current={catalog}
      />

      {/* the core refuses while the catalog has products or roles: its message is shown in the modal */}
      <ConfirmModal
        opened={confirmOpened}
        title={t('catalogDetail.delete.title', { catalog: catalog.name })}
        message={
          catalog.productCount > 0
            ? t('catalogDetail.delete.notEmpty', { count: catalog.productCount })
            : t('catalogDetail.delete.message')
        }
        confirmLabel={t('common.delete')}
        pending={deleteCatalog.isPending}
        error={deleteCatalog.error}
        onConfirm={confirmDelete}
        onClose={closeDeleteConfirm}
      />
    </Group>
  );
}

function ProductsSection({ catalog }: { catalog: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: products, isPending, error } = useCatalogProducts(catalog);
  const [createOpened, { open: openCreate, close: closeCreate }] = useDisclosure();

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>{t('catalogDetail.products.title')}</Title>
          <Text c="dimmed">{t('catalogDetail.products.subtitle')}</Text>
        </div>
        <Button onClick={openCreate}>{t('catalogDetail.products.new')}</Button>
      </Group>

      {isPending && <Loader size="sm" />}

      {error && (
        <Alert color="red" title={t('catalogDetail.products.loadError')}>
          {error.message}
        </Alert>
      )}

      {products && products.length === 0 && <Text c="dimmed">{t('catalogDetail.products.empty')}</Text>}

      {products && products.length > 0 && (
        <Table.ScrollContainer minWidth={720}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('catalogDetail.products.columns.product')}</Table.Th>
                <Table.Th>{t('catalogDetail.products.columns.description')}</Table.Th>
                <Table.Th>{t('catalogDetail.products.columns.kind')}</Table.Th>
                <Table.Th>{t('catalogDetail.products.columns.features')}</Table.Th>
                <Table.Th>{t('catalogDetail.products.columns.params')}</Table.Th>
                <Table.Th>{t('catalogDetail.products.columns.organisations')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {products.map((product) => (
                <Table.Tr key={product.name}>
                  <Table.Td>
                    <Anchor component={Link} to={`/catalogs/${catalog}/products/${product.name}`} fw={500}>
                      {product.displayName ?? product.name}
                    </Anchor>
                    <Text size="xs" c="dimmed" ff="monospace">
                      {product.name}
                    </Text>
                  </Table.Td>
                  <Table.Td c="dimmed">{product.description}</Table.Td>
                  {/* counts, not lists: the lists are on the page of the product. The names are in the tooltip */}
                  <Table.Td>
                    <ProductKindBadge metered={product.metered} />
                  </Table.Td>
                  <Table.Td title={product.features.map((feature) => feature.name).join(', ')}>
                    {product.features.length}
                  </Table.Td>
                  <Table.Td title={product.params.map((param) => param.name).join(', ')}>{product.params.length}</Table.Td>
                  <Table.Td>
                    <OrgCount catalog={catalog} product={product.name} count={product.orgCount} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {/* once created, the product opens on its page */}
      <ProductModal
        catalog={catalog}
        opened={createOpened}
        onClose={closeCreate}
        current={null}
        onSaved={(product) => navigate(`/catalogs/${catalog}/products/${product.name}`)}
      />
    </Stack>
  );
}

// The catalog roles: valid for the whole catalog, given by the org admins (grantable) or by the realm owner only
function RolesSection({ catalog }: { catalog: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: roles, isPending, error } = useCatalogRoles(catalog);
  const [createOpened, { open: openCreate, close: closeCreate }] = useDisclosure();

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>{t('catalogDetail.roles.title')}</Title>
          <Text c="dimmed">{t('catalogDetail.roles.subtitle')}</Text>
        </div>
        <Button onClick={openCreate}>{t('catalogDetail.roles.new')}</Button>
      </Group>

      {isPending && <Loader size="sm" />}

      {error && (
        <Alert color="red" title={t('catalogDetail.roles.loadError')}>
          {error.message}
        </Alert>
      )}

      {roles && roles.length === 0 && <Text c="dimmed">{t('catalogDetail.roles.empty')}</Text>}

      {roles && roles.length > 0 && (
        <Table.ScrollContainer minWidth={600}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('catalogDetail.roles.columns.role')}</Table.Th>
                <Table.Th>{t('catalogDetail.roles.columns.description')}</Table.Th>
                <Table.Th>{t('catalogDetail.roles.columns.grantedBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {roles.map((role) => (
                <Table.Tr key={role.name}>
                  <Table.Td>
                    <Anchor component={Link} to={`/catalogs/${catalog}/roles/${role.name}`} fw={500}>
                      {role.displayName ?? role.name}
                    </Anchor>
                    <Text size="xs" c="dimmed" ff="monospace">
                      {role.name}
                    </Text>
                  </Table.Td>
                  <Table.Td c="dimmed">{role.description}</Table.Td>
                  <Table.Td>
                    <RoleKindBadge orgGrantable={role.orgGrantable} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {/* once created, the role opens on its page */}
      <CatalogRoleModal
        catalog={catalog}
        opened={createOpened}
        onClose={closeCreate}
        current={null}
        onSaved={(role) => navigate(`/catalogs/${catalog}/roles/${role.name}`)}
      />
    </Stack>
  );
}

// Who gives a catalog role: the org admins through their teams, or the realm owner only. Shared with CatalogRolePage
export function RoleKindBadge({ orgGrantable }: { orgGrantable: boolean }) {
  const { t } = useTranslation();
  return (
    <Badge variant="light" color={orgGrantable ? 'blue' : 'orange'} tt="none">
      {orgGrantable ? t('catalogDetail.roles.grantable') : t('catalogDetail.roles.reserved')}
    </Badge>
  );
}

// The number of organisations a product is assigned to. A link to the organisations list, filtered on the product:
// the list stays readable with many organisations, and the filter is already there.
export function OrgCount({ catalog, product, count }: { catalog: string; product: string; count: number }) {
  const { t } = useTranslation();

  if (count === 0) {
    return (
      <Text c="dimmed" size="sm">
        {t('catalogDetail.products.notAssigned')}
      </Text>
    );
  }
  // URLSearchParams escapes the values, like in api/organisations.ts
  const query = new URLSearchParams({ catalog, product });
  return (
    <Anchor component={Link} to={`/organisations?${query}`} size="sm">
      {t('catalogDetail.products.orgCount', { count })}
    </Anchor>
  );
}

// The kind of a product, derived by the core from its features: simple (a quota of product) or metered (a quota per
// metered feature). Shared with the page of a product
export function ProductKindBadge({ metered }: { metered: boolean }) {
  const { t } = useTranslation();
  return (
    // tt="none": the default upper case makes the label too wide for a table column
    <Badge variant="light" color={metered ? 'grape' : 'gray'} tt="none" style={{ flexShrink: 0 }}>
      {metered ? t('productDetail.kind.metered') : t('productDetail.kind.simple')}
    </Badge>
  );
}

// What the API of the realm owner receives: makes the link between this screen and the tokens
// The credentials the APIs of the catalog introspect the tokens with (lightweight tokens, see the login client).
// The core never gives a secret back: the realm owner generates a new one, shown here until the page is left.
function IntrospectionSection({ catalog }: { catalog: string }) {
  const { t } = useTranslation();
  const regenerate = useRegenerateSecret(catalog);
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();
  const credentials = regenerate.data;

  function closeConfirmModal() {
    // keep the credentials of a previous success: only a failed attempt is cleared
    if (regenerate.isError) {
      regenerate.reset();
    }
    closeConfirm();
  }

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Group justify="space-between">
          <Text fw={600}>{t('catalogDetail.introspection.title')}</Text>
          <Button variant="default" onClick={openConfirm}>
            {t('catalogDetail.introspection.generate')}
          </Button>
        </Group>
        <Text size="sm" c="dimmed">
          {t('catalogDetail.introspection.description')}
        </Text>
        <Text size="sm" c="dimmed">
          {t('catalogDetail.introspection.audience')}
        </Text>

        {credentials && (
          <>
            <Alert color="yellow">{t('catalogDetail.introspection.shownOnce')}</Alert>
            <Table>
              <Table.Tbody>
                {[
                  { label: t('catalogDetail.introspection.clientId'), value: credentials.clientId },
                  { label: t('catalogDetail.introspection.clientSecret'), value: credentials.clientSecret },
                  { label: t('catalogDetail.introspection.url'), value: credentials.introspectionUrl },
                ].map((row) => (
                  <Table.Tr key={row.label}>
                    <Table.Td w={180}>
                      <Text size="sm" c="dimmed">
                        {row.label}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Group gap="xs" wrap="nowrap">
                        <Code>{row.value}</Code>
                        <CopyValue value={row.value} />
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </>
        )}
      </Stack>

      <ConfirmModal
        opened={confirmOpened}
        title={t('catalogDetail.introspection.confirm.title', { catalog })}
        message={t('catalogDetail.introspection.confirm.message')}
        confirmLabel={t('catalogDetail.introspection.confirm.button')}
        confirmColor="orange"
        pending={regenerate.isPending}
        error={regenerate.error}
        onConfirm={() => regenerate.mutate(undefined, { onSuccess: closeConfirm })}
        onClose={closeConfirmModal}
      />
    </Paper>
  );
}

function TokenHint({ catalog }: { catalog: string }) {
  const { t } = useTranslation();
  // the token format of the core (rights in resource_access, quotas and parameters in flat claims named
  // "<catalog>.<product>" and "<catalog>.<product>.<feature>"), with an example of each kind: billing is simple
  // (access role, a quota of product), shop is metered (no access role, a claim per metered feature with its quotas).
  // "/" is the pool of the org, the root of the org
  const token = {
    resource_access: { [catalog]: { roles: ['billing', 'billing:read', 'shop:write', 'shop:search'] } },
    [`${catalog}.billing`]: { quotas: [{ pool: '/', limit: 1000, cycle: 'monthly' }] },
    [`${catalog}.shop.search`]: { quotas: [{ pool: '/', limit: 200, cycle: 'daily' }] },
  };

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Text fw={600}>{t('catalogDetail.token.title')}</Text>
        <Text size="sm" c="dimmed">
          {t('catalogDetail.token.scope')} <Code>scope={catalog}</Code>
        </Text>
        <Code block>{JSON.stringify(token, null, 2)}</Code>
        <Text size="sm" c="dimmed">
          {t('catalogDetail.token.kinds')}
        </Text>
        <Text size="sm" c="dimmed">
          {t('catalogDetail.token.usage')}
        </Text>
      </Stack>
    </Paper>
  );
}
