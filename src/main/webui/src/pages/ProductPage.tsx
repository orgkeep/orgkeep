import { useState } from 'react';
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
import { useDeleteProduct, useDeleteProductRole, useProduct } from '../api/catalogs';
import type { Product, ProductRole } from '../api/products';
import ConfirmModal from '../components/ConfirmModal';
import { OrgCount } from './CatalogPage';
import ProductModal from './ProductModal';
import ProductRoleModal from './ProductRoleModal';

// One product of a catalog (realm owner): its header, its roles, its parameters, and what it gives in the tokens.
export default function ProductPage() {
  const { t } = useTranslation();
  // both parts of the route /catalogs/:catalog/products/:product
  const params = useParams();
  const catalog = params.catalog!;
  const name = params.product!;
  const { data: product, isPending, error } = useProduct(catalog, name);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('productDetail.loadError', { product: `${catalog}/${name}` })} maw={1100}>
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
        <Anchor component={Link} to={`/catalogs/${catalog}`} ff="monospace">
          {catalog}
        </Anchor>
        <Text ff="monospace">{product.name}</Text>
      </Breadcrumbs>

      <ProductHeader product={product} />
      <RolesSection product={product} />
      <ParamsSection product={product} />
      <TokenHint product={product} />
    </Stack>
  );
}

function ProductHeader({ product }: { product: Product }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const deleteProduct = useDeleteProduct(product.catalog);
  const [editOpened, { open: openEdit, close: closeEdit }] = useDisclosure();
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();

  function closeDeleteConfirm() {
    deleteProduct.reset();
    closeConfirm();
  }

  function confirmDelete() {
    deleteProduct.mutate(product.name, { onSuccess: () => navigate(`/catalogs/${product.catalog}`) });
  }

  return (
    <Group justify="space-between" align="flex-start">
      <Stack gap={4}>
        <Group gap="sm" align="baseline">
          <Title order={2}>{product.displayName ?? product.name}</Title>
          <Code>
            {product.catalog}/{product.name}
          </Code>
        </Group>
        {product.description && <Text c="dimmed">{product.description}</Text>}
        <OrgCount catalog={product.catalog} product={product.name} count={product.orgCount} />
      </Stack>

      <Group>
        <Button variant="default" onClick={openEdit}>
          {t('common.edit')}
        </Button>
        <Button color="red" variant="outline" onClick={openConfirm}>
          {t('common.delete')}
        </Button>
      </Group>

      {/* key: a new form each time the product changes (useForm reads its initial values once) */}
      <ProductModal
        key={JSON.stringify(product)}
        catalog={product.catalog}
        opened={editOpened}
        onClose={closeEdit}
        current={product}
      />

      {/* the core refuses while the product is assigned to an organisation */}
      <ConfirmModal
        opened={confirmOpened}
        title={t('productDetail.delete.title', { product: product.displayName ?? product.name })}
        message={
          product.orgCount > 0
            ? t('productDetail.delete.assigned', { count: product.orgCount })
            : t('productDetail.delete.message')
        }
        confirmLabel={t('common.delete')}
        pending={deleteProduct.isPending}
        error={deleteProduct.error}
        onConfirm={confirmDelete}
        onClose={closeDeleteConfirm}
      />
    </Group>
  );
}

// The roles of the product: the options an organisation buys, or the permissions it gives to its teams
function RolesSection({ product }: { product: Product }) {
  const { t } = useTranslation();
  const deleteRole = useDeleteProductRole(product.catalog, product.name);
  // null = closed, 'new' = "Add a role", a role = "Edit" of this role
  const [roleTarget, setRoleTarget] = useState<ProductRole | 'new' | null>(null);
  const [removeTarget, setRemoveTarget] = useState<ProductRole | null>(null);

  function closeRemoveConfirm() {
    deleteRole.reset();
    setRemoveTarget(null);
  }

  function confirmRemove() {
    if (removeTarget) {
      deleteRole.mutate(removeTarget.name, { onSuccess: closeRemoveConfirm });
    }
  }

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>{t('productDetail.roles.title')}</Title>
          <Text c="dimmed">{t('productDetail.roles.subtitle')}</Text>
        </div>
        <Button variant="light" onClick={() => setRoleTarget('new')}>
          {t('productDetail.roles.add')}
        </Button>
      </Group>

      {product.roles.length === 0 ? (
        <Text c="dimmed">{t('productDetail.roles.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('productDetail.roles.columns.name')}</Table.Th>
                <Table.Th>{t('productDetail.roles.columns.displayName')}</Table.Th>
                <Table.Th>{t('productDetail.roles.columns.inToken')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {product.roles.map((role) => (
                <Table.Tr key={role.name}>
                  <Table.Td ff="monospace">{role.name}</Table.Td>
                  <Table.Td>{role.displayName}</Table.Td>
                  {/* a product role is prefixed by its product in the tokens */}
                  <Table.Td>
                    <Code>
                      {product.name}:{role.name}
                    </Code>
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end" wrap="nowrap">
                      <Button variant="subtle" size="xs" onClick={() => setRoleTarget(role)}>
                        {t('common.edit')}
                      </Button>
                      <Button variant="subtle" size="xs" color="red" onClick={() => setRemoveTarget(role)}>
                        {t('common.remove')}
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      <ProductRoleModal
        key={roleTarget === 'new' || roleTarget === null ? 'new' : roleTarget.name}
        catalog={product.catalog}
        product={product.name}
        opened={roleTarget !== null}
        onClose={() => setRoleTarget(null)}
        current={roleTarget === 'new' ? null : roleTarget}
      />

      <ConfirmModal
        opened={removeTarget !== null}
        title={t('productDetail.roles.removeTitle', { role: removeTarget?.name ?? '' })}
        message={t('productDetail.roles.removeMessage')}
        confirmLabel={t('common.remove')}
        pending={deleteRole.isPending}
        error={deleteRole.error}
        onConfirm={confirmRemove}
        onClose={closeRemoveConfirm}
      />
    </Stack>
  );
}

// The parameter schema, read only here: it is changed with the rest of the product ("Edit" in the header),
// the core replaces the whole schema in one call
function ParamsSection({ product }: { product: Product }) {
  const { t } = useTranslation();

  return (
    <Stack>
      <div>
        <Title order={3}>{t('productDetail.params.title')}</Title>
        <Text c="dimmed">{t('productDetail.params.subtitle')}</Text>
      </div>

      {product.params.length === 0 ? (
        <Text c="dimmed">{t('productDetail.params.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={640}>
          <Table withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('productForm.params.name')}</Table.Th>
                <Table.Th>{t('productForm.params.type')}</Table.Th>
                <Table.Th>{t('productForm.params.required')}</Table.Th>
                <Table.Th>{t('productForm.params.defaultValue')}</Table.Th>
                <Table.Th>{t('productForm.params.values')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {product.params.map((param) => (
                <Table.Tr key={param.name}>
                  <Table.Td ff="monospace">{param.name}</Table.Td>
                  <Table.Td>{t(`productForm.params.types.${param.type}`)}</Table.Td>
                  <Table.Td>{param.required ? t('productForm.params.true') : t('productForm.params.false')}</Table.Td>
                  <Table.Td ff="monospace">{param.defaultValue ?? '-'}</Table.Td>
                  <Table.Td>
                    {param.values.length === 0
                      ? '-'
                      : param.values.map((value) => (
                          <Badge key={value} variant="light" mr={4} tt="none">
                            {value}
                          </Badge>
                        ))}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

// What a user receives in the token once a team of their organisation gives the product, with the real names
function TokenHint({ product }: { product: Product }) {
  const { t } = useTranslation();
  const roles = [product.name, ...product.roles.map((role) => `${product.name}:${role.name}`)];
  // the defaults stand for the values an organisation negotiates
  const params = Object.fromEntries(product.params.map((param) => [param.name, param.defaultValue ?? '...']));
  const claim: Record<string, unknown> = { quotas: [{ pool: 'acme', limit: 1000, cycle: 'monthly' }] };
  if (product.params.length > 0) {
    claim.params = params;
  }
  // the part of the token about this product, in the token format of the core (rights in resource_access, quotas and parameters in the claim of the catalog)
  const token = {
    resource_access: { [product.catalog]: { roles } },
    [product.catalog]: { [product.name]: claim },
  };

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Text fw={600}>{t('productDetail.token.title')}</Text>
        {/* null, 2: indented by 2 spaces, like a pretty printer */}
        <Code block>{JSON.stringify(token, null, 2)}</Code>
        <Text size="sm" c="dimmed">
          {t('productDetail.token.hint')}
        </Text>
      </Stack>
    </Paper>
  );
}
