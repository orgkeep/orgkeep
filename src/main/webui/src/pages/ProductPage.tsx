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
import { useDeleteFeature, useDeleteProduct, useProduct } from '../api/catalogs';
import type { Feature, Product } from '../api/products';
import ConfirmModal from '../components/ConfirmModal';
import { OrgCount, ProductKindBadge } from './CatalogPage';
import FeatureModal from './FeatureModal';
import ProductModal from './ProductModal';

// One product of a catalog (realm owner): its header, its features, its parameters, and what it gives in the tokens.
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
      <FeaturesSection product={product} />
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
          <ProductKindBadge metered={product.metered} />
        </Group>
        {product.description && <Text c="dimmed">{product.description}</Text>}
        <Text size="sm" c="dimmed">
          {product.metered ? t('productDetail.kind.meteredHint') : t('productDetail.kind.simpleHint')}
        </Text>
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

// The features of the product: the permissions an organisation buys, and the capacities it buys with a quota each
function FeaturesSection({ product }: { product: Product }) {
  const { t } = useTranslation();
  const deleteFeature = useDeleteFeature(product.catalog, product.name);
  // null = closed, 'new' = "Add a feature", a feature = "Edit" of this feature
  const [featureTarget, setFeatureTarget] = useState<Feature | 'new' | null>(null);
  const [removeTarget, setRemoveTarget] = useState<Feature | null>(null);

  function closeRemoveConfirm() {
    deleteFeature.reset();
    setRemoveTarget(null);
  }

  function confirmRemove() {
    if (removeTarget) {
      deleteFeature.mutate(removeTarget.name, { onSuccess: closeRemoveConfirm });
    }
  }

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>{t('productDetail.features.title')}</Title>
          <Text c="dimmed">{t('productDetail.features.subtitle')}</Text>
        </div>
        <Button variant="light" onClick={() => setFeatureTarget('new')}>
          {t('productDetail.features.add')}
        </Button>
      </Group>

      {product.features.length === 0 ? (
        <Text c="dimmed">{t('productDetail.features.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('productDetail.features.columns.name')}</Table.Th>
                <Table.Th>{t('productDetail.features.columns.displayName')}</Table.Th>
                <Table.Th>{t('productDetail.features.columns.inToken')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {product.features.map((feature) => (
                <Table.Tr key={feature.name}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Text ff="monospace" size="sm">
                        {feature.name}
                      </Text>
                      {feature.metered && (
                        <Badge variant="light" color="grape" size="sm">
                          {t('productForm.features.metered')}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>{feature.displayName}</Table.Td>
                  {/* a feature is prefixed by its product in the tokens */}
                  <Table.Td>
                    <Code>
                      {product.name}:{feature.name}
                    </Code>
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end" wrap="nowrap">
                      <Button variant="subtle" size="xs" onClick={() => setFeatureTarget(feature)}>
                        {t('common.edit')}
                      </Button>
                      <Button variant="subtle" size="xs" color="red" onClick={() => setRemoveTarget(feature)}>
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

      <FeatureModal
        key={featureTarget === 'new' || featureTarget === null ? 'new' : featureTarget.name}
        catalog={product.catalog}
        product={product.name}
        opened={featureTarget !== null}
        onClose={() => setFeatureTarget(null)}
        current={featureTarget === 'new' ? null : featureTarget}
      />

      {/* the core refuses a metered feature bought by an organisation, or the last one of an assigned product:
          its message is shown in the modal */}
      <ConfirmModal
        opened={removeTarget !== null}
        title={t('productDetail.features.removeTitle', { feature: removeTarget?.name ?? '' })}
        message={t('productDetail.features.removeMessage')}
        confirmLabel={t('common.remove')}
        pending={deleteFeature.isPending}
        error={deleteFeature.error}
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
  const features = product.features.map((feature) => `${product.name}:${feature.name}`);
  // a metered product has no access role: the API checks the feature a call needs
  const roles = product.metered ? features : [product.name, ...features];
  // the part of the token about this product, in the token format of the core: rights in resource_access, quotas
  // and parameters in flat claims, one per product ("prod.billing") and one per metered feature ("prod.billing.scan")
  const productClaim = `${product.catalog}.${product.name}`;
  const token: Record<string, unknown> = { resource_access: { [product.catalog]: { roles } } };
  // the claim of the product: a simple product has "quotas", the pool of the org ("/", the root of the org); the
  // defaults of the parameters stand for the values an organisation negotiates
  const claim: Record<string, unknown> = {};
  if (!product.metered) {
    claim.quotas = [{ pool: '/', limit: 1000, cycle: 'monthly' }];
  }
  if (product.params.length > 0) {
    claim.params = Object.fromEntries(product.params.map((param) => [param.name, param.defaultValue ?? '...']));
  }
  // a metered product with no parameter has no claim of its own, only those of its metered features
  if (Object.keys(claim).length > 0) {
    token[productClaim] = claim;
  }
  if (product.metered) {
    product.features
      .filter((feature) => feature.metered)
      .forEach((feature) => {
        token[`${productClaim}.${feature.name}`] = { quotas: [{ pool: '/', limit: 200, cycle: 'daily' }] };
      });
  }

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Text fw={600}>{t('productDetail.token.title')}</Text>
        {/* null, 2: indented by 2 spaces, like a pretty printer */}
        <Code block>{JSON.stringify(token, null, 2)}</Code>
        <Text size="sm" c="dimmed">
          {product.metered ? t('productDetail.token.meteredHint') : t('productDetail.token.hint')}
        </Text>
      </Stack>
    </Paper>
  );
}
