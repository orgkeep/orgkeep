import { useState } from 'react';
import {
  Alert,
  Anchor,
  Breadcrumbs,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Paper,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import {
  type CatalogRole,
  type RoleHolder,
  useCatalogRole,
  useDeleteCatalogRole,
  useGrantRoleToUser,
  useRevokeRoleFromUser,
  useRoleHolders,
} from '../api/catalogs';
import { useOrganisations, useOrgMembers } from '../api/organisations';
import ConfirmModal from '../components/ConfirmModal';
import { RoleKindBadge } from './CatalogPage';
import CatalogRoleModal from './CatalogRoleModal';
import { personName } from './personName';

// One catalog role (realm owner): its header, the users holding it directly, and where it is in the tokens.
export default function CatalogRolePage() {
  const { t } = useTranslation();
  // both always set on this route, hence the "!"
  const catalog = useParams().catalog!;
  const name = useParams().role!;
  const { data: role, isPending, error } = useCatalogRole(catalog, name);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('catalogRole.loadError', { role: name })} maw={1100}>
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
        <Text ff="monospace">{role.name}</Text>
      </Breadcrumbs>

      <RoleHeader role={role} />
      <HoldersSection catalog={catalog} role={role.name} />

      <Paper withBorder p="md">
        <Stack gap="xs">
          <Text fw={600}>{t('catalogRole.token.title')}</Text>
          <Code block>{JSON.stringify({ resource_access: { [catalog]: { roles: [role.name] } } }, null, 2)}</Code>
          <Text size="sm" c="dimmed">
            {t('catalogRole.token.scope')} <Code>scope={catalog}</Code>
          </Text>
        </Stack>
      </Paper>
    </Stack>
  );
}

// Display name, name, who gives it, description, and the edit and delete buttons
function RoleHeader({ role }: { role: CatalogRole }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const deleteRole = useDeleteCatalogRole(role.catalog);
  const [editOpened, { open: openEdit, close: closeEdit }] = useDisclosure();
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();

  function closeDeleteConfirm() {
    deleteRole.reset();
    closeConfirm();
  }

  return (
    <Group justify="space-between" align="flex-start">
      <Stack gap={4}>
        <Group gap="sm" align="baseline">
          <Title order={2}>{role.displayName ?? role.name}</Title>
          <Code>{role.name}</Code>
          <RoleKindBadge orgGrantable={role.orgGrantable} />
        </Group>
        {role.description && <Text c="dimmed">{role.description}</Text>}
        <Text size="sm" c="dimmed">
          {role.orgGrantable ? t('catalogRole.grantableHint') : t('catalogRole.reservedHint')}
        </Text>
      </Stack>

      <Group>
        <Button variant="default" onClick={openEdit}>
          {t('common.edit')}
        </Button>
        <Button color="red" variant="outline" onClick={openConfirm}>
          {t('common.delete')}
        </Button>
      </Group>

      {/* key: the form reads its initial values once, a changed role gives a new form */}
      <CatalogRoleModal
        key={`${role.displayName}|${role.description}|${role.orgGrantable}`}
        catalog={role.catalog}
        opened={editOpened}
        onClose={closeEdit}
        current={role}
      />

      <ConfirmModal
        opened={confirmOpened}
        title={t('catalogRole.delete.title', { role: role.name })}
        message={t('catalogRole.delete.message')}
        confirmLabel={t('common.delete')}
        pending={deleteRole.isPending}
        error={deleteRole.error}
        // the page of a deleted role would answer 404: back to the catalog once the core confirmed
        onConfirm={() => deleteRole.mutate(role.name, { onSuccess: () => navigate(`/catalogs/${role.catalog}`) })}
        onClose={closeDeleteConfirm}
      />
    </Group>
  );
}

// The users holding the role directly, given by the realm owner (not those who get it through a team)
function HoldersSection({ catalog, role }: { catalog: string; role: string }) {
  const { t } = useTranslation();
  const { data: holders, isPending, error } = useRoleHolders(catalog, role);
  const revoke = useRevokeRoleFromUser(catalog, role);
  // the holder whose removal waits for a confirmation, null when the modal is closed
  const [toRemove, setToRemove] = useState<RoleHolder | null>(null);

  function closeRemove() {
    revoke.reset();
    setToRemove(null);
  }

  return (
    <Stack>
      <div>
        <Title order={3}>{t('catalogRole.users.title')}</Title>
        <Text c="dimmed">{t('catalogRole.users.subtitle')}</Text>
      </div>

      <GrantToUser catalog={catalog} role={role} holderIds={(holders ?? []).map((holder) => holder.userId)} />

      {isPending && <Loader size="sm" />}
      {error && (
        <Alert color="red" title={t('catalogRole.users.loadError')}>
          {error.message}
        </Alert>
      )}
      {holders && holders.length === 0 && <Text c="dimmed">{t('catalogRole.users.empty')}</Text>}
      {holders && holders.length > 0 && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('catalogRole.users.columns.user')}</Table.Th>
              <Table.Th>{t('catalogRole.users.columns.email')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {holders.map((holder) => (
              <Table.Tr key={holder.userId}>
                <Table.Td ff="monospace">{holder.username}</Table.Td>
                <Table.Td>{holder.email}</Table.Td>
                <Table.Td align="right">
                  <Button size="xs" variant="subtle" color="red" onClick={() => setToRemove(holder)}>
                    {t('common.remove')}
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <ConfirmModal
        opened={toRemove !== null}
        title={t('catalogRole.users.removeTitle', { role })}
        message={t('catalogRole.users.removeMessage', { user: toRemove?.email ?? toRemove?.username ?? '' })}
        confirmLabel={t('common.remove')}
        pending={revoke.isPending}
        error={revoke.error}
        onConfirm={() => toRemove && revoke.mutate(toRemove.userId, { onSuccess: closeRemove })}
        onClose={closeRemove}
      />
    </Stack>
  );
}

// Two steps: an organisation, then one of its members. The core only gives a catalog role to a member of an org
interface GrantProps {
  catalog: string;
  role: string;
  // the users who already hold the role: not offered again
  holderIds: string[];
}

function GrantToUser({ catalog, role, holderIds }: GrantProps) {
  const { t } = useTranslation();
  const [org, setOrg] = useState<string | null>(null);
  // all the organisations (first page of the core, 100 max), without filter
  const organisations = useOrganisations({ search: '', catalog: '', product: '' });

  return (
    <Group align="flex-end">
      <Select
        label={t('catalogRole.users.organisation')}
        placeholder={t('catalogRole.users.organisationPlaceholder')}
        data={(organisations.data ?? []).map((organisation) => organisation.name)}
        value={org}
        onChange={setOrg}
        searchable
        w={240}
      />
      {/* the members are loaded once an organisation is chosen: the hook needs it */}
      {org && <MemberPicker key={org} catalog={catalog} role={role} holderIds={holderIds} org={org} />}
    </Group>
  );
}

function MemberPicker({ catalog, role, holderIds, org }: GrantProps & { org: string }) {
  const { t } = useTranslation();
  const members = useOrgMembers(org);
  const grant = useGrantRoleToUser(catalog, role);
  const candidates = (members.data ?? []).filter((member) => !holderIds.includes(member.id));

  return (
    <Stack gap={4}>
      {/* a Select used as an action, like the member picker of a team: choosing a member gives the role */}
      <Select
        label={t('catalogRole.users.member')}
        placeholder={t('catalogRole.users.memberPlaceholder')}
        data={candidates.map((member) => ({ value: member.id, label: personName(member) }))}
        value={null}
        onChange={(userId) => userId && grant.mutate(userId)}
        searchable
        disabled={grant.isPending}
        w={280}
      />
      {grant.error && (
        <Text size="sm" c="red">
          {grant.error.message}
        </Text>
      )}
    </Stack>
  );
}
