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
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  type OrgDetails,
  type OrgMember,
  type OrgProduct,
  useDeleteOrganisation,
  useOrganisation,
  useOrgMembers,
  useOrgProducts,
  useSetOrgEnabled,
  useUnassignProduct,
} from '../api/organisations';
import { type Product, productKey, useProducts } from '../api/products';
import { type UserGroup, useUserGroups } from '../api/teams';
import ConfirmModal from '../components/ConfirmModal';
import AssignProductModal from './AssignProductModal';
import { ROLE_COLORS } from './orgRoles';
import { personName } from './personName';

// The tabs of the page. The selected one is in the URL (?tab=products) like the product filter of the list:
// the product count of the list links straight to the products tab.
const TABS = ['members', 'products', 'teams'] as const;
type Tab = (typeof TABS)[number];

// Any other value of ?tab (or none) gives the default tab
function toTab(value: string | null): Tab {
  return TABS.find((tab) => tab === value) ?? 'members';
}

// The detail of an organisation (realm owner): its header, its members and its products.
export default function OrganisationPage() {
  const { t } = useTranslation();
  // the :org part of the route /organisations/:org. Always set on this route, hence the "!"
  const org = useParams().org!;
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = toTab(searchParams.get('tab'));

  const { data: details, isPending, error } = useOrganisation(org);
  // the lists are loaded at once: the tabs show their counts
  const members = useOrgMembers(org);
  const products = useOrgProducts(org);
  // the API of the org admins, opened to the realm owner by the core
  const teams = useUserGroups(org);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    // e.g. 404 for an unknown organisation, typed in the URL
    return (
      <Alert color="red" title={t('orgDetail.loadError', { org })} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  function changeTab(value: string | null) {
    // the members tab is the default: no "?tab=members" in the URL
    const next = toTab(value);
    setSearchParams(next === 'members' ? {} : { tab: next });
  }

  return (
    <Stack maw={1100}>
      <Breadcrumbs>
        <Anchor component={Link} to="/organisations">
          {t('orgDetail.breadcrumb')}
        </Anchor>
        <Text ff="monospace">{org}</Text>
      </Breadcrumbs>

      <OrgHeader details={details} />

      <Tabs value={tab} onChange={changeTab}>
        <Tabs.List>
          <Tabs.Tab value="members" rightSection={<Count value={members.data?.length} />}>
            {t('orgDetail.tabs.members')}
          </Tabs.Tab>
          <Tabs.Tab value="products" rightSection={<Count value={products.data?.length} />}>
            {t('orgDetail.tabs.products')}
          </Tabs.Tab>
          <Tabs.Tab value="teams" rightSection={<Count value={teams.data?.length} />}>
            {t('orgDetail.tabs.teams')}
          </Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="members" pt="md">
          <MembersTab members={members.data} isPending={members.isPending} error={members.error} />
        </Tabs.Panel>
        <Tabs.Panel value="products" pt="md">
          <ProductsTab org={org} products={products.data} isPending={products.isPending} error={products.error} />
        </Tabs.Panel>
        <Tabs.Panel value="teams" pt="md">
          <TeamsTab org={org} teams={teams.data} isPending={teams.isPending} error={teams.error} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

// The number next to a tab title, once its list is loaded
function Count({ value }: { value: number | undefined }) {
  return value === undefined ? null : (
    <Text size="xs" c="dimmed" fw={500}>
      {value}
    </Text>
  );
}

// Name, status, domains, owners, org client, and the delete button
function OrgHeader({ details }: { details: OrgDetails }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const deleteOrg = useDeleteOrganisation();
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();

  function closeDeleteConfirm() {
    deleteOrg.reset();
    closeConfirm();
  }

  function confirmDelete() {
    // the page of a deleted org would answer 404: back to the list once the core confirmed
    deleteOrg.mutate(details.name, { onSuccess: () => navigate('/organisations') });
  }

  return (
    <Group justify="space-between" align="flex-start">
      <Stack gap="xs">
        <Group gap="sm">
          <Title order={2} ff="monospace">
            {details.name}
          </Title>
          <Badge color={details.enabled ? 'green' : 'gray'} variant="light">
            {details.enabled ? t('organisations.status.active') : t('organisations.status.disabled')}
          </Badge>
        </Group>
        <Group gap="lg">
          <Text c="dimmed">
            {t('orgDetail.domains')}{' '}
            <Text span c="var(--mantine-color-text)" fw={500}>
              {details.emailDomains.length === 0 ? t('orgDetail.none') : details.emailDomains.join(', ')}
            </Text>
          </Text>
          <Text c="dimmed">
            {t('orgDetail.owners', { count: details.owners.length })}{' '}
            <Text span c="var(--mantine-color-text)" fw={500}>
              {details.owners.length === 0 ? t('organisations.noOwner') : details.owners.map(personName).join(', ')}
            </Text>
          </Text>
          <Text c="dimmed">
            {t('orgDetail.client')} <Code>{details.clientId}</Code>
          </Text>
        </Group>
      </Stack>

      <Group gap="sm">
        <StatusButton details={details} />
        <Button color="red" variant="outline" onClick={openConfirm}>
          {t('orgDetail.delete.button')}
        </Button>
      </Group>

      <ConfirmModal
        opened={confirmOpened}
        title={t('orgDetail.delete.title', { org: details.name })}
        message={t('orgDetail.delete.message')}
        confirmLabel={t('orgDetail.delete.confirm')}
        pending={deleteOrg.isPending}
        error={deleteOrg.error}
        onConfirm={confirmDelete}
        onClose={closeDeleteConfirm}
      />
    </Group>
  );
}

// Disable (members can no longer log in) or enable again, both behind a confirmation
function StatusButton({ details }: { details: OrgDetails }) {
  const { t } = useTranslation();
  const setEnabled = useSetOrgEnabled(details.name);
  const [opened, { open, close }] = useDisclosure();
  // the action the button offers: the opposite of the current status
  const action = details.enabled ? 'disable' : 'enable';

  function closeModal() {
    setEnabled.reset();
    close();
  }

  return (
    <>
      <Button color={details.enabled ? 'orange' : 'green'} variant="outline" onClick={open}>
        {t(`orgDetail.status.${action}.button`)}
      </Button>
      <ConfirmModal
        opened={opened}
        title={t(`orgDetail.status.${action}.title`, { org: details.name })}
        message={t(`orgDetail.status.${action}.message`)}
        confirmLabel={t(`orgDetail.status.${action}.confirm`)}
        confirmColor={details.enabled ? 'orange' : 'green'}
        pending={setEnabled.isPending}
        error={setEnabled.error}
        onConfirm={() => setEnabled.mutate(!details.enabled, { onSuccess: closeModal })}
        onClose={closeModal}
      />
    </>
  );
}

interface TeamsTabProps {
  org: string;
  // undefined while loading
  teams: UserGroup[] | undefined;
  isPending: boolean;
  error: Error | null;
}

// Read only, counts rather than lists: the org admins manage their teams. A team opens on a read-only page
function TeamsTab({ org, teams, isPending, error }: TeamsTabProps) {
  const { t } = useTranslation();

  if (isPending) {
    return <Loader size="sm" />;
  }
  if (error) {
    return (
      <Alert color="red" title={t('orgDetail.teams.loadError')}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack gap="sm">
      <Text c="dimmed" size="sm">
        {t('orgDetail.teams.hint')}
      </Text>
      <Table.ScrollContainer minWidth={560}>
        <Table highlightOnHover withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('orgDetail.teams.columns.team')}</Table.Th>
              <Table.Th>{t('orgDetail.teams.columns.members')}</Table.Th>
              <Table.Th>{t('orgDetail.teams.columns.products')}</Table.Th>
              <Table.Th>{t('orgDetail.teams.columns.catalogRoles')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(teams ?? []).map((team) => (
              <Table.Tr key={team.id}>
                <Table.Td>
                  <Anchor component={Link} to={`/organisations/${org}/teams/${team.name}`} ff="monospace" fw={500}>
                    {team.name}
                  </Anchor>
                  {team.predefined && (
                    <Badge variant="light" color="gray" ml="xs" size="sm">
                      {t('teams.predefined')}
                    </Badge>
                  )}
                </Table.Td>
                <Table.Td>{team.memberIds.length}</Table.Td>
                <Table.Td>{team.products.length}</Table.Td>
                <Table.Td>{team.catalogRoles.length}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </Stack>
  );
}

interface MembersTabProps {
  // undefined while loading
  members: OrgMember[] | undefined;
  isPending: boolean;
  error: Error | null;
}

// Read only: the org admins add and remove members on their own page (OrgMembersPage)
function MembersTab({ members, isPending, error }: MembersTabProps) {
  const { t } = useTranslation();

  if (isPending) {
    return <Loader size="sm" />;
  }
  if (error) {
    return (
      <Alert color="red" title={t('orgDetail.members.loadError')}>
        {error.message}
      </Alert>
    );
  }
  if (!members || members.length === 0) {
    return <Text c="dimmed">{t('orgDetail.members.empty')}</Text>;
  }

  return (
    <Table.ScrollContainer minWidth={640}>
      <Table highlightOnHover withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('orgDetail.members.columns.name')}</Table.Th>
            <Table.Th>{t('orgDetail.members.columns.email')}</Table.Th>
            <Table.Th>{t('orgDetail.members.columns.role')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {members.map((member) => (
            <Table.Tr key={member.id}>
              <Table.Td fw={500}>{personName(member)}</Table.Td>
              <Table.Td c="dimmed">{member.email}</Table.Td>
              <Table.Td>
                <Badge color={ROLE_COLORS[member.role]} variant="light">
                  {t(`orgDetail.roles.${member.role}`)}
                </Badge>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

// What the org negotiated: one quota for a simple product, one short line per metered feature for a metered one
// (an org buys few of them: a list stays short)
function QuotaCell({ product, numberFormat }: { product: OrgProduct; numberFormat: Intl.NumberFormat }) {
  const { t } = useTranslation();
  const featureQuotas = Object.entries(product.featureQuotas);

  if (featureQuotas.length > 0) {
    return (
      <Stack gap={2}>
        {featureQuotas.map(([feature, quota]) => (
          <Text key={feature} size="sm">
            <Text span ff="monospace" size="sm">
              {feature}
            </Text>{' '}
            {t('orgDetail.products.quotaValue', {
              quota: numberFormat.format(quota.quota),
              cycle: t(`orgDetail.cycles.${quota.cycle}`),
            })}
          </Text>
        ))}
      </Stack>
    );
  }
  if (product.quota === null || product.cycle === null) {
    return <Text c="dimmed">-</Text>;
  }
  return (
    <Text fw={600} size="sm">
      {t('orgDetail.products.quotaValue', {
        quota: numberFormat.format(product.quota),
        cycle: t(`orgDetail.cycles.${product.cycle}`),
      })}
    </Text>
  );
}

interface ProductsTabProps {
  org: string;
  products: OrgProduct[] | undefined;
  isPending: boolean;
  error: Error | null;
}

function ProductsTab({ org, products, isPending, error }: ProductsTabProps) {
  const { t, i18n } = useTranslation();
  // the products of the catalogs give the display names, and the products that can be assigned
  const { data: catalogProducts } = useProducts();
  const unassign = useUnassignProduct(org);

  // Which modal is open, and for which assignment. A single state for the assign modal:
  // null = closed, 'new' = "Assign a product", an assignment = "Edit" of this assignment
  const [assignTarget, setAssignTarget] = useState<OrgProduct | 'new' | null>(null);
  const [removeTarget, setRemoveTarget] = useState<OrgProduct | null>(null);

  function displayName(assignment: OrgProduct): string {
    const product = catalogProducts?.find((p: Product) => p.catalog === assignment.catalog && p.name === assignment.product);
    return product?.displayName ?? assignment.product;
  }

  function closeRemoveConfirm() {
    unassign.reset();
    setRemoveTarget(null);
  }

  function confirmRemove() {
    if (removeTarget) {
      unassign.mutate({ catalog: removeTarget.catalog, product: removeTarget.product }, { onSuccess: closeRemoveConfirm });
    }
  }

  if (isPending) {
    return <Loader size="sm" />;
  }
  if (error) {
    return (
      <Alert color="red" title={t('orgDetail.products.loadError')}>
        {error.message}
      </Alert>
    );
  }

  const assigned = products ?? [];
  // the language of the page decides the thousand separator: 50,000 in English, 50 000 in French
  const numberFormat = new Intl.NumberFormat(i18n.language);

  return (
    <Stack>
      <Group justify="space-between">
        <Text c="dimmed">{t('orgDetail.products.subtitle')}</Text>
        <Button onClick={() => setAssignTarget('new')}>{t('orgDetail.products.assign')}</Button>
      </Group>

      {assigned.length === 0 ? (
        <Text c="dimmed">{t('orgDetail.products.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={900}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('orgDetail.products.columns.product')}</Table.Th>
                <Table.Th>{t('orgDetail.products.columns.quota')}</Table.Th>
                <Table.Th>{t('orgDetail.products.columns.boughtFeatures')}</Table.Th>
                <Table.Th>{t('orgDetail.products.columns.params')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {assigned.map((product) => (
                <Table.Tr key={productKey(product.catalog, product.product)}>
                  <Table.Td>
                    <Text fw={500}>{displayName(product)}</Text>
                    <Text size="xs" c="dimmed" ff="monospace">
                      {productKey(product.catalog, product.product)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <QuotaCell product={product} numberFormat={numberFormat} />
                  </Table.Td>
                  {/* a count, not a list: the names are in the tooltip, and in the form of the assignment */}
                  <Table.Td title={product.boughtFeatures.join(', ')}>
                    {product.boughtFeatures.length === 0 ? '-' : product.boughtFeatures.length}
                  </Table.Td>
                  <Table.Td c="dimmed" ff="monospace" fz="sm">
                    {Object.keys(product.params).length === 0
                      ? '-'
                      : Object.entries(product.params)
                          .map(([name, value]) => `${name}=${value}`)
                          .join(', ')}
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end" wrap="nowrap">
                      <Button variant="subtle" size="xs" onClick={() => setAssignTarget(product)}>
                        {t('orgDetail.products.edit')}
                      </Button>
                      <Button variant="subtle" size="xs" color="red" onClick={() => setRemoveTarget(product)}>
                        {t('orgDetail.products.remove')}
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {/* key: a new key creates a new component, so the form starts from the values of the chosen assignment
          (useForm only reads its initial values once) */}
      <AssignProductModal
        key={assignTarget === 'new' || assignTarget === null ? 'new' : productKey(assignTarget.catalog, assignTarget.product)}
        org={org}
        opened={assignTarget !== null}
        onClose={() => setAssignTarget(null)}
        products={catalogProducts ?? []}
        assigned={assigned}
        current={assignTarget === 'new' ? null : assignTarget}
      />

      <ConfirmModal
        opened={removeTarget !== null}
        title={t('orgDetail.products.removeTitle', { product: removeTarget ? displayName(removeTarget) : '' })}
        message={t('orgDetail.products.removeMessage')}
        confirmLabel={t('orgDetail.products.remove')}
        pending={unassign.isPending}
        error={unassign.error}
        onConfirm={confirmRemove}
        onClose={closeRemoveConfirm}
      />
    </Stack>
  );
}
