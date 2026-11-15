import { useState } from 'react';
import { ActionIcon, Alert, Anchor, Badge, Button, Group, Loader, Menu, Modal, Stack, Table, Text, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import {
  type ServiceAccount,
  type ServiceAccountCredentials,
  useDeleteServiceAccount,
  useOrgCatalogs,
  useRotateServiceAccountSecret,
  useServiceAccounts,
  useSetServiceAccountEnabled,
} from '../api/serviceAccounts';
import ConfirmModal from '../components/ConfirmModal';
import GearIcon from '../components/GearIcon';
import ServiceAccountSecretModal from '../components/ServiceAccountSecretModal';
import ServiceAccountModal from './ServiceAccountModal';

interface ServiceAccountsSectionProps {
  org: string;
  // true for the realm owner (the Service accounts tab of /organisations/:org): the team links go to its own routes
  realmOwner?: boolean;
}

// What a confirmation is about: a service account and the action to run on it
type Action = { kind: 'rotate' | 'disable' | 'enable' | 'delete'; account: ServiceAccount };

// The service accounts of an org: list, create, edit, rotate the secret, disable / enable, delete.
// Shared by the page of the org admins and the tab of the realm owner: the core enforces who may do what,
// this component only shows the screens.
export default function ServiceAccountsSection({ org, realmOwner = false }: ServiceAccountsSectionProps) {
  const { t } = useTranslation();
  const { data: accounts, isPending, error } = useServiceAccounts(org);
  const catalogs = useOrgCatalogs(org);
  const rotate = useRotateServiceAccountSecret(org);
  const setEnabled = useSetServiceAccountEnabled(org);
  const remove = useDeleteServiceAccount(org);

  // null = closed, 'new' = creation, a service account = edition
  const [formTarget, setFormTarget] = useState<ServiceAccount | 'new' | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  // the teams of a service account, shown in a modal when its count is clicked
  const [teamsOf, setTeamsOf] = useState<ServiceAccount | null>(null);
  // The secret shown once. It lives in the state of this component only while the modal is open: never in the query
  // cache, and the core does not give it again. rotated: after a rotation, the text says the old secret is dead
  const [secret, setSecret] = useState<{ credentials: ServiceAccountCredentials; rotated: boolean } | null>(null);

  function closeSecret() {
    setSecret(null);
    // the mutation keeps its data (the new secret) until it is reset
    rotate.reset();
  }

  // the mutation of the current action, to show its pending state and its error in the confirmation modal
  const running = action?.kind === 'rotate' ? rotate : action?.kind === 'delete' ? remove : setEnabled;

  function closeAction() {
    rotate.reset();
    setEnabled.reset();
    remove.reset();
    setAction(null);
  }

  function confirmAction() {
    if (!action) {
      return;
    }
    const name = action.account.name;
    switch (action.kind) {
      case 'rotate':
        rotate.mutate(name, {
          onSuccess: (credentials) => {
            setAction(null);
            setSecret({ credentials, rotated: true });
          },
        });
        break;
      case 'delete':
        remove.mutate(name, { onSuccess: closeAction });
        break;
      default:
        setEnabled.mutate({ name, enabled: action.kind === 'enable' }, { onSuccess: closeAction });
    }
  }

  const teamPath = (team: string) => (realmOwner ? `/organisations/${org}/teams/${team}` : `/orgs/${org}/teams/${team}`);

  if (isPending) {
    return <Loader size="sm" />;
  }
  if (error) {
    return (
      <Alert color="red" title={t('serviceAccounts.loadError', { org })}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start">
        <Text c="dimmed" size="sm" maw={720}>
          {t('serviceAccounts.hint')}
        </Text>
        {/* the form needs the catalogs of the org: not before they are loaded */}
        <Button onClick={() => setFormTarget('new')} disabled={!catalogs.data}>
          {t('serviceAccounts.create')}
        </Button>
      </Group>
      {catalogs.error && (
        <Alert color="red" title={t('serviceAccounts.catalogsError')}>
          {catalogs.error.message}
        </Alert>
      )}

      {accounts.length === 0 ? (
        <Text c="dimmed">{t('serviceAccounts.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('serviceAccounts.columns.name')}</Table.Th>
                <Table.Th>{t('serviceAccounts.columns.description')}</Table.Th>
                <Table.Th>{t('serviceAccounts.columns.status')}</Table.Th>
                <Table.Th ta="right">{t('serviceAccounts.columns.catalogs')}</Table.Th>
                <Table.Th ta="right">{t('serviceAccounts.columns.teams')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {accounts.map((account) => (
                <Table.Tr key={account.name}>
                  <Table.Td>
                    {/* the realm owner has a page per service account (its tokens mode); the org admins do not */}
                    {realmOwner ? (
                      <Anchor component={Link} to={`/organisations/${org}/service-accounts/${account.name}`} ff="monospace" fw={500}>
                        {account.name}
                      </Anchor>
                    ) : (
                      <Text ff="monospace" fw={500}>
                        {account.name}
                      </Text>
                    )}
                    <Text size="xs" c="dimmed" ff="monospace">
                      {account.clientId}
                    </Text>
                  </Table.Td>
                  <Table.Td>{account.description}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={account.enabled ? 'green' : 'gray'}>
                      {t(account.enabled ? 'serviceAccounts.status.enabled' : 'serviceAccounts.status.disabled')}
                    </Badge>
                  </Table.Td>
                  <Table.Td ta="right">
                    {/* a count, the names in a tooltip: the list can grow */}
                    <Tooltip label={account.defaultCatalogs.join(', ')} disabled={account.defaultCatalogs.length === 0}>
                      <span>{account.defaultCatalogs.length}</span>
                    </Tooltip>
                  </Table.Td>
                  <Table.Td ta="right">
                    {account.teams.length === 0 ? (
                      0
                    ) : (
                      <Anchor component="button" type="button" onClick={() => setTeamsOf(account)}>
                        {account.teams.length}
                      </Anchor>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Group justify="flex-end">
                      <Menu position="bottom-end">
                        <Menu.Target>
                          {/* an icon alone: aria-label gives screen readers the word the icon replaces */}
                          <ActionIcon variant="subtle" aria-label={t('serviceAccounts.actions')}>
                            <GearIcon />
                          </ActionIcon>
                        </Menu.Target>
                        <Menu.Dropdown>
                          <Menu.Item onClick={() => setFormTarget(account)} disabled={!catalogs.data}>{t('common.edit')}</Menu.Item>
                          <Menu.Item onClick={() => setAction({ kind: 'rotate', account })}>
                            {t('serviceAccounts.rotate.button')}
                          </Menu.Item>
                          <Menu.Item onClick={() => setAction({ kind: account.enabled ? 'disable' : 'enable', account })}>
                            {t(account.enabled ? 'serviceAccounts.disable.button' : 'serviceAccounts.enable.button')}
                          </Menu.Item>
                          <Menu.Item color="red" onClick={() => setAction({ kind: 'delete', account })}>
                            {t('common.delete')}
                          </Menu.Item>
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

      {/* mounted once the catalogs are known: a new service account preselects the only catalog of the org.
          key: a new key gives a new form, starting from the values of the service account to edit */}
      {catalogs.data && (
        <ServiceAccountModal
          // The form reads its initial values only once, when it is created. A different key makes React create a new
          // form: so the preselected catalog follows the catalogs of the org (their list is in the key)
          key={formTarget === 'new' || formTarget === null ? `new|${catalogs.data.join()}` : formTarget.name}
          org={org}
          opened={formTarget !== null}
          onClose={() => setFormTarget(null)}
          catalogs={catalogs.data}
          current={formTarget === 'new' ? null : formTarget}
          onCreated={(credentials) => setSecret({ credentials, rotated: false })}
        />
      )}

      <ServiceAccountSecretModal credentials={secret?.credentials ?? null} rotated={secret?.rotated ?? false} onClose={closeSecret} />

      <ConfirmModal
        opened={action !== null}
        title={t(`serviceAccounts.${action?.kind ?? 'rotate'}.title`, { name: action?.account.name ?? '' })}
        message={t(`serviceAccounts.${action?.kind ?? 'rotate'}.message`)}
        confirmLabel={t(action?.kind === 'delete' ? 'common.delete' : `serviceAccounts.${action?.kind ?? 'rotate'}.button`)}
        // red only for the deletion, orange for the others (an action to think about, not a loss)
        confirmColor={action?.kind === 'delete' ? 'red' : 'orange'}
        pending={running.isPending}
        error={running.error}
        onConfirm={confirmAction}
        onClose={closeAction}
      />

      <Modal
        opened={teamsOf !== null}
        onClose={() => setTeamsOf(null)}
        title={t('serviceAccounts.teamsTitle', { name: teamsOf?.name ?? '' })}
      >
        <Stack gap="xs">
          {(teamsOf?.teams ?? []).map((team) => (
            <Anchor key={team} component={Link} to={teamPath(team)} ff="monospace">
              {team}
            </Anchor>
          ))}
        </Stack>
      </Modal>
    </Stack>
  );
}
