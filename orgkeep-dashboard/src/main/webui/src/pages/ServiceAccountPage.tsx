import {
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
  Center,
  Code,
  Group,
  Loader,
  Paper,
  Stack,
  Switch,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import {
  type ServiceAccount,
  useServiceAccount,
  useServiceAccountSettings,
  useUpdateServiceAccountSettings,
} from '../api/serviceAccounts';
import ConfirmModal from '../components/ConfirmModal';
import CopyValue from '../components/CopyValue';
import SuspensionBadge from '../components/SuspensionBadge';

// A service account of an org, for the realm owner only (route /organisations/:org/service-accounts/:sa): what the
// org admins also see, plus the mode of its tokens, which only the realm owner changes. The other actions (edit,
// rotate the secret, disable, delete) stay in the list of the organisation.
export default function ServiceAccountPage() {
  const { t } = useTranslation();
  // the path parameters of the route. Always set on this route, hence the cast to strings (without it they are typed string | undefined)
  const { org, sa } = useParams() as { org: string; sa: string };
  const { data: account, isPending, error } = useServiceAccount(org, sa);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('serviceAccount.loadError', { name: sa })} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack maw={1100} gap="xl">
      <Stack gap="xs">
        <Breadcrumbs>
          <Anchor component={Link} to={`/organisations/${org}?tab=serviceAccounts`}>
            {t('serviceAccounts.title', { org })}
          </Anchor>
          <Text ff="monospace">{account.name}</Text>
        </Breadcrumbs>
        <Group gap="sm">
          <Title order={2} ff="monospace">
            {account.name}
          </Title>
          {account.suspension ? (
            <SuspensionBadge suspension={account.suspension} realmOwner />
          ) : (
            <Badge variant="light" color={account.enabled ? 'green' : 'gray'}>
              {t(account.enabled ? 'serviceAccounts.status.enabled' : 'serviceAccounts.status.disabled')}
            </Badge>
          )}
        </Group>
        {account.description && <Text c="dimmed">{account.description}</Text>}
      </Stack>
      <Details account={account} />
      <LightweightTokensSection org={org} name={account.name} />
    </Stack>
  );
}

// Read only
function Details({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const rows = [
    { label: t('serviceAccount.clientId'), value: <Code>{account.clientId}</Code>, copy: account.clientId },
    { label: t('serviceAccounts.columns.catalogs'), value: account.defaultCatalogs.join(', ') || '-' },
    { label: t('serviceAccounts.columns.teams'), value: account.teams.join(', ') || '-' },
  ];

  return (
    <Paper withBorder p="md">
      <Table>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.label}>
              <Table.Td w={180}>
                <Text size="sm" c="dimmed">
                  {row.label}
                </Text>
              </Table.Td>
              <Table.Td>
                <Group gap="xs" wrap="nowrap">
                  {row.value}
                  {row.copy && <CopyValue value={row.copy} />}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Paper>
  );
}

// The mode of the tokens of this service account: a switch saved on its own, with a confirmation when it is turned on.
// Turned on, every API receiving its tokens must introspect them first. Turned off, nothing breaks: an API that
// introspects works with full tokens too. The core decides who may change it (the realm owner only).
function LightweightTokensSection({ org, name }: { org: string; name: string }) {
  const { t } = useTranslation();
  const { data: settings, error } = useServiceAccountSettings(org, name);
  const update = useUpdateServiceAccountSettings(org, name);
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();

  function closeConfirmModal() {
    update.reset();
    closeConfirm();
  }

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Group justify="space-between">
          <Text fw={600}>{t('serviceAccount.lightweight.title')}</Text>
          {settings && (
            <Switch
              checked={settings.lightweightTokens}
              disabled={update.isPending}
              label={settings.lightweightTokens ? t('serviceAccount.lightweight.on') : t('serviceAccount.lightweight.off')}
              // on: confirm first; off: saved at once
              onChange={(event) =>
                event.currentTarget.checked ? openConfirm() : update.mutate({ lightweightTokens: false })
              }
            />
          )}
        </Group>
        <Text size="sm" c="dimmed">
          {t('serviceAccount.lightweight.description')}
        </Text>
        <Text size="sm" c="dimmed">
          {t('serviceAccount.lightweight.introspection')}{' '}
          <Anchor component={Link} to="/catalogs">
            {t('loginClient.lightweight.catalogsLink')}
          </Anchor>
        </Text>
        {settings?.lightweightTokens && (
          <Text size="sm" c="dimmed">
            {t('loginClient.lightweight.marker')} <Code>"lightweight": true</Code>
          </Text>
        )}
        {error && (
          <Alert color="red" title={t('serviceAccount.lightweight.loadError')}>
            {error.message}
          </Alert>
        )}
        {update.error && !confirmOpened && (
          <Alert color="red" title={t('common.failed')}>
            {update.error.message}
          </Alert>
        )}
      </Stack>

      <ConfirmModal
        opened={confirmOpened}
        title={t('serviceAccount.lightweight.confirm.title', { name })}
        message={t('serviceAccount.lightweight.confirm.message')}
        confirmLabel={t('loginClient.lightweight.confirm.button')}
        confirmColor="orange"
        pending={update.isPending}
        error={update.error}
        onConfirm={() => update.mutate({ lightweightTokens: true }, { onSuccess: closeConfirm })}
        onClose={closeConfirmModal}
      />
    </Paper>
  );
}
