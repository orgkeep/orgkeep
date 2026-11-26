import { useEffect, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Menu,
  Modal,
  Pagination,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  type OrgDetails,
  type OrgMember,
  type OrgProduct,
  PAGE_SIZE,
  useDeleteOrganisation,
  useGrantOwnership,
  useLiftMemberSuspension,
  useOrganisation,
  useOrgMembers,
  useOrgProducts,
  useRevokeOwnership,
  useSetOrgEnabled,
  useSuspendMember,
  useUnassignProduct,
} from '../api/organisations';
import { type Product, productKey, useProducts } from '../api/products';
import { useServiceAccounts } from '../api/serviceAccounts';
import { type UserGroup, useUserGroups } from '../api/teams';
import ConfirmModal from '../components/ConfirmModal';
import GearIcon from '../components/GearIcon';
import SuspendModal from '../components/SuspendModal';
import SuspensionBadge from '../components/SuspensionBadge';
import TokenPreviewModal from '../components/TokenPreviewModal';
import AssignProductModal from './AssignProductModal';
import { ROLE_COLORS } from './orgRoles';
import { personName } from './personName';
import ServiceAccountsSection from './ServiceAccountsSection';
import MemberName from "../components/MemberName";
import ResendInvitationButton from '../components/ResendInvitationButton';

// The tabs of the page. The selected one is in the URL (?tab=products) like the product filter of the list:
// the product count of the list links straight to the products tab.
const TABS = ['members', 'products', 'teams', 'serviceAccounts'] as const;
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
  // the lists are loaded at once: the tabs show their counts. Members: just the total, unfiltered (the members
  // tab has its own search and page), max 1 so this call reads almost nothing
  const members = useOrgMembers(org, { max: 1 });
  const products = useOrgProducts(org);
  // the API of the org admins, opened to the realm owner by the core
  const teams = useUserGroups(org);
  const serviceAccounts = useServiceAccounts(org);

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
          <Tabs.Tab value="members" rightSection={<Count value={members.data?.total} />}>
            {t('orgDetail.tabs.members')}
          </Tabs.Tab>
          <Tabs.Tab value="products" rightSection={<Count value={products.data?.length} />}>
            {t('orgDetail.tabs.products')}
          </Tabs.Tab>
          <Tabs.Tab value="teams" rightSection={<Count value={teams.data?.length} />}>
            {t('orgDetail.tabs.teams')}
          </Tabs.Tab>
          <Tabs.Tab value="serviceAccounts" rightSection={<Count value={serviceAccounts.data?.length} />}>
            {t('orgDetail.tabs.serviceAccounts')}
          </Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="members" pt="md">
          <MembersTab org={org} />
        </Tabs.Panel>
        <Tabs.Panel value="products" pt="md">
          <ProductsTab org={org} products={products.data} isPending={products.isPending} error={products.error} />
        </Tabs.Panel>
        <Tabs.Panel value="teams" pt="md">
          <TeamsTab org={org} teams={teams.data} isPending={teams.isPending} error={teams.error} />
        </Tabs.Panel>
        <Tabs.Panel value="serviceAccounts" pt="md">
          <ServiceAccountsSection org={org} realmOwner />
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
  org: string;
}

// What the confirmation modal below is about: making a member owner, or taking ownership away from one
type OwnershipAction = { kind: 'makeOwner' | 'removeOwnership'; member: OrgMember };

// The org admins add and remove members on their own page (OrgMembersPage): here, search, paging, ownership
// (realm owner only: make owner, remove ownership, transfer ownership) and a preview of the token of a member
function MembersTab({ org }: MembersTabProps) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search.trim(), 300);
  const [page, setPage] = useState(1);
  const { data: memberPage, isPending, error } = useOrgMembers(org, { search: debouncedSearch, page });
  // null = the preview modal is closed
  const [previewTarget, setPreviewTarget] = useState<OrgMember | null>(null);
  // null = no confirmation open
  const [ownershipAction, setOwnershipAction] = useState<OwnershipAction | null>(null);
  // the owner being transferred from, null = the transfer modal is closed
  const [transferTarget, setTransferTarget] = useState<OrgMember | null>(null);
  const grantOwnership = useGrantOwnership(org);
  const revokeOwnership = useRevokeOwnership(org);
  // suspend / edit reason and lift suspension: realm owner only, this whole tab is
  const suspend = useSuspendMember(org);
  const liftSuspension = useLiftMemberSuspension(org);
  // null = closed. Set to open SuspendModal, either to suspend (no suspension yet) or to edit the reason
  const [suspendTarget, setSuspendTarget] = useState<OrgMember | null>(null);
  // null = closed, the member whose "Lift suspension" confirmation is open
  const [liftTarget, setLiftTarget] = useState<OrgMember | null>(null);
  const totalPages = memberPage ? Math.ceil(memberPage.total / PAGE_SIZE) : 0;

  function closeOwnershipAction() {
    grantOwnership.reset();
    revokeOwnership.reset();
    setOwnershipAction(null);
  }

  function confirmOwnershipAction() {
    if (!ownershipAction) {
      return;
    }
    const userId = ownershipAction.member.id;
    if (ownershipAction.kind === 'makeOwner') {
      grantOwnership.mutate(userId, { onSuccess: closeOwnershipAction });
    } else {
      revokeOwnership.mutate(userId, { onSuccess: closeOwnershipAction });
    }
  }

  // the mutation of the current confirmation, to show its pending state and its error in the modal
  const ownershipRunning = ownershipAction?.kind === 'removeOwnership' ? revokeOwnership : grantOwnership;

  function closeSuspend() {
    suspend.reset();
    setSuspendTarget(null);
  }

  function closeLift() {
    liftSuspension.reset();
    setLiftTarget(null);
  }

  // A page can become empty when the list shrinks (same dead end as OrgMembersPage): bring it back within range
  useEffect(() => {
    if (totalPages > 0 && page > totalPages) {
      setPage(totalPages);
    }
  }, [totalPages, page]);

  function changeSearch(value: string) {
    setPage(1);
    setSearch(value);
  }

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

  const members = memberPage.items;

  return (
    <Stack>
      <TextInput
        placeholder={t('members.searchPlaceholder')}
        aria-label={t('members.searchLabel')}
        value={search}
        onChange={(event) => changeSearch(event.currentTarget.value)}
        w={320}
        maw="100%"
      />

      {members.length === 0 ? (
        <Text c="dimmed">{debouncedSearch ? t('orgDetail.members.noMatch') : t('orgDetail.members.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={640}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('orgDetail.members.columns.name')}</Table.Th>
                <Table.Th>{t('orgDetail.members.columns.email')}</Table.Th>
                <Table.Th>{t('orgDetail.members.columns.role')}</Table.Th>
                <Table.Th>{t('orgDetail.members.columns.status')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {members.map((member) => (
                <Table.Tr key={member.id} opacity={member.enabled === false ? 0.6 : undefined}>
                  <Table.Td fw={500}>
                    <MemberName member={member}/>
                  </Table.Td>
                  <Table.Td c="dimmed">{member.email}</Table.Td>
                  <Table.Td>
                    <Badge color={ROLE_COLORS[member.role]} variant="light">
                      {t(`orgDetail.roles.${member.role}`)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    {member.suspension ? (
                      <SuspensionBadge suspension={member.suspension} realmOwner />
                    ) : (
                      <Badge color={member.enabled === false ? 'gray' : 'green'} variant="light">
                        {t(member.enabled === false ? 'members.status.disabled' : 'members.status.active')}
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Group justify="flex-end" align="flex-start" wrap="nowrap">
                      <ResendInvitationButton org={org} member={member} />
                      <Button variant="subtle" size="xs" onClick={() => setPreviewTarget(member)}>
                        {t('tokenPreview.button')}
                      </Button>
                      <Menu position="bottom-end">
                        <Menu.Target>
                          {/* an icon alone: aria-label gives screen readers the word the icon replaces */}
                          <ActionIcon variant="subtle" aria-label={t('orgDetail.members.actions')}>
                            <GearIcon />
                          </ActionIcon>
                        </Menu.Target>
                        <Menu.Dropdown>
                          {member.role === 'ORG_OWNER' ? (
                            <>
                              <Menu.Item onClick={() => setOwnershipAction({ kind: 'removeOwnership', member })}>
                                {t('orgDetail.members.removeOwnership.button')}
                              </Menu.Item>
                              <Menu.Item onClick={() => setTransferTarget(member)}>
                                {t('orgDetail.members.transferOwnership.button')}
                              </Menu.Item>
                            </>
                          ) : (
                            <Menu.Item onClick={() => setOwnershipAction({ kind: 'makeOwner', member })}>
                              {t('orgDetail.members.makeOwner.button')}
                            </Menu.Item>
                          )}
                          <Menu.Divider />
                          <Menu.Item onClick={() => setSuspendTarget(member)}>
                            {t(member.suspension ? 'suspension.suspend.editButton' : 'suspension.suspend.button')}
                          </Menu.Item>
                          {member.suspension && (
                            <Menu.Item onClick={() => setLiftTarget(member)}>{t('suspension.lift.button')}</Menu.Item>
                          )}
                        </Menu.Dropdown>
                      </Menu>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {totalPages > 1 && (
        <Group justify="center">
          <Pagination value={page} onChange={setPage} total={totalPages} />
        </Group>
      )}

      {/* key: a new modal per member, so the catalog selection starts again from every catalog of the org */}
      <TokenPreviewModal
        key={previewTarget?.id ?? 'closed'}
        org={org}
        member={previewTarget}
        onClose={() => setPreviewTarget(null)}
      />

      <ConfirmModal
        opened={ownershipAction !== null}
        title={t(`orgDetail.members.${ownershipAction?.kind ?? 'makeOwner'}.title`, {
          member: ownershipAction ? personName(ownershipAction.member) : '',
        })}
        message={t(`orgDetail.members.${ownershipAction?.kind ?? 'makeOwner'}.message`, {
          member: ownershipAction ? personName(ownershipAction.member) : '',
        })}
        confirmLabel={t(`orgDetail.members.${ownershipAction?.kind ?? 'makeOwner'}.confirm`)}
        // not destructive: green to grant, orange to take access away, like StatusButton
        confirmColor={ownershipAction?.kind === 'removeOwnership' ? 'orange' : 'green'}
        pending={ownershipRunning.isPending}
        error={ownershipRunning.error}
        onConfirm={confirmOwnershipAction}
        onClose={closeOwnershipAction}
      />

      <TransferOwnershipModal org={org} owner={transferTarget} onClose={() => setTransferTarget(null)} />

      {/* key: a new form per target, so "Edit reason" starts from its current reason (useForm reads it once) */}
      <SuspendModal
        key={suspendTarget?.id ?? 'closed'}
        opened={suspendTarget !== null}
        onClose={closeSuspend}
        target={suspendTarget ? personName(suspendTarget) : ''}
        currentReason={suspendTarget?.suspension?.reason ?? null}
        pending={suspend.isPending}
        error={suspend.error}
        onSubmit={(reason) => suspend.mutate({ userId: suspendTarget!.id, reason }, { onSuccess: closeSuspend })}
      />

      <ConfirmModal
        opened={liftTarget !== null}
        title={t('suspension.lift.title', { target: liftTarget ? personName(liftTarget) : '' })}
        message={t('suspension.lift.message')}
        confirmLabel={t('suspension.lift.confirm')}
        confirmColor="green"
        pending={liftSuspension.isPending}
        error={liftSuspension.error}
        onConfirm={() => liftTarget && liftSuspension.mutate(liftTarget.id, { onSuccess: closeLift })}
        onClose={closeLift}
      />
    </Stack>
  );
}

// Picks another member of the organisation to make owner, then takes ownership away from the current owner.
// Only once the PUT succeeded: a failure there is shown and nothing else happens. A failure of the DELETE after
// a successful PUT leaves both as owners; the message says so, and trying again (idempotent PUT, then DELETE)
// finishes the job.
function TransferOwnershipModal({ org, owner, onClose }: { org: string; owner: OrgMember | null; onClose: () => void }) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search.trim(), 300);
  const { data: candidates } = useOrgMembers(org, { search: debouncedSearch, max: 20, enabled: owner !== null });
  const [chosen, setChosen] = useState<string | null>(null);
  const grant = useGrantOwnership(org);
  const revoke = useRevokeOwnership(org);
  // true once the new owner is confirmed: a later DELETE error then means both are owners, not just the new one
  const [granted, setGranted] = useState(false);

  // never offer the current owner as their own successor
  const options = (candidates?.items ?? []).filter((member) => member.id !== owner?.id);

  function close() {
    grant.reset();
    revoke.reset();
    setChosen(null);
    setGranted(false);
    setSearch('');
    onClose();
  }

  function confirmTransfer() {
    if (!chosen || !owner) {
      return;
    }
    // reset a previous failed attempt, so a new failure doesn't show stale state (e.g. both errors at once)
    revoke.reset();
    setGranted(false);
    grant.mutate(chosen, {
      onSuccess: () => {
        setGranted(true);
        revoke.mutate(owner.id, { onSuccess: close });
      },
    });
  }

  return (
    <Modal
      opened={owner !== null}
      onClose={close}
      title={t('orgDetail.members.transferOwnership.title', { member: owner ? personName(owner) : '' })}
    >
      <Stack>
        <Text>{t('orgDetail.members.transferOwnership.message', { member: owner ? personName(owner) : '' })}</Text>
        <Select
          label={t('orgDetail.members.transferOwnership.pickLabel')}
          placeholder={t('orgDetail.members.transferOwnership.pickPlaceholder')}
          data={options.map((member) => ({ value: member.id, label: personName(member) }))}
          value={chosen}
          onChange={setChosen}
          searchable
          searchValue={search}
          onSearchChange={setSearch}
        />
        {grant.error && (
          <Alert color="red" title={t('common.failed')}>
            {grant.error.message}
          </Alert>
        )}
        {granted && revoke.error && (
          <Alert color="red" title={t('orgDetail.members.transferOwnership.removeFailedTitle')}>
            {t('orgDetail.members.transferOwnership.removeFailedMessage')}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {t('common.cancel')}
          </Button>
          <Button loading={grant.isPending || revoke.isPending} disabled={!chosen} onClick={confirmTransfer}>
            {t('orgDetail.members.transferOwnership.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
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
