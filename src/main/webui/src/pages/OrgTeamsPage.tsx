import { useState } from 'react';
import { Alert, Anchor, Badge, Button, Center, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { type UserGroup, useDeleteUserGroup, useUserGroups } from '../api/teams';
import ConfirmModal from '../components/ConfirmModal';
import CreateTeamModal from './CreateTeamModal';

// The teams of an org (its user groups), managed by its org admins. Each row shows counts, not lists: the detail
// of a team (members, products, roles) is on its own page.
export default function OrgTeamsPage() {
  const { t } = useTranslation();
  const org = useParams().org!;
  const navigate = useNavigate();
  const { data: teams, isPending, error } = useUserGroups(org);
  const deleteTeam = useDeleteUserGroup(org);
  const [createOpened, { open: openCreate, close: closeCreate }] = useDisclosure();
  const [deleteTarget, setDeleteTarget] = useState<UserGroup | null>(null);

  function closeDeleteConfirm() {
    deleteTeam.reset();
    setDeleteTarget(null);
  }

  function confirmDelete() {
    if (deleteTarget) {
      deleteTeam.mutate(deleteTarget.name, { onSuccess: closeDeleteConfirm });
    }
  }

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('teams.loadError', { org })} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack maw={1100}>
      <Group justify="space-between">
        <Title order={2}>{t('teams.title', { org })}</Title>
        <Button onClick={openCreate}>{t('teams.create')}</Button>
      </Group>
      <Text c="dimmed">{t('teams.subtitle')}</Text>

      <Table.ScrollContainer minWidth={640}>
        <Table highlightOnHover withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('teams.columns.name')}</Table.Th>
              <Table.Th ta="right">{t('teams.columns.members')}</Table.Th>
              <Table.Th ta="right">{t('teams.columns.products')}</Table.Th>
              <Table.Th ta="right">{t('teams.columns.catalogRoles')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {teams.map((team) => (
              <Table.Tr key={team.id}>
                <Table.Td>
                  <Group gap="xs">
                    <Anchor component={Link} to={`/orgs/${org}/teams/${team.name}`} ff="monospace" fw={500}>
                      {team.name}
                    </Anchor>
                    {team.predefined && (
                      <Badge variant="light" color="gray" size="sm">
                        {t('teams.predefined')}
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td ta="right">{team.memberIds.length}</Table.Td>
                <Table.Td ta="right">{team.products.length}</Table.Td>
                <Table.Td ta="right">{team.catalogRoles.length}</Table.Td>
                <Table.Td>
                  <Group justify="flex-end">
                    {/* admin and users come with the org: the core refuses to delete them (409) */}
                    {!team.predefined && (
                      <Button variant="subtle" size="xs" color="red" onClick={() => setDeleteTarget(team)}>
                        {t('common.delete')}
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      <CreateTeamModal
        org={org}
        opened={createOpened}
        onClose={closeCreate}
        onCreated={(team) => navigate(`/orgs/${org}/teams/${team.name}`)}
      />

      <ConfirmModal
        opened={deleteTarget !== null}
        title={t('teams.deleteTitle', { team: deleteTarget?.name ?? '' })}
        message={t('teams.deleteMessage')}
        confirmLabel={t('common.delete')}
        pending={deleteTeam.isPending}
        error={deleteTeam.error}
        onConfirm={confirmDelete}
        onClose={closeDeleteConfirm}
      />
    </Stack>
  );
}
