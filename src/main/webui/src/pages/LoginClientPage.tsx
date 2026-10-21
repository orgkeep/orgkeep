import {
  Alert,
  Anchor,
  Button,
  Center,
  Code,
  CopyButton,
  Group,
  Loader,
  MultiSelect,
  Paper,
  Stack,
  Table,
  TagsInput,
  Text,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { useCatalogs } from '../api/catalogs';
import { type LoginClient, type UpdateLoginClientRequest, useLoginClient, useUpdateLoginClient } from '../api/loginClient';

// The login client of the realm (realm owner): what the developers of the realm owner put in their applications,
// and the URIs Keycloak accepts for them. Without a redirect URI, nobody can log in through it.
export default function LoginClientPage() {
  const { t } = useTranslation();
  const { data: client, isPending, error } = useLoginClient();

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('loginClient.loadError')} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack maw={1100}>
      <div>
        <Title order={2}>{t('loginClient.title')}</Title>
        <Text c="dimmed">{t('loginClient.subtitle')}</Text>
      </div>
      <ConnectionDetails client={client} />
      <SettingsForm client={client} />
    </Stack>
  );
}

// Read only: the values a developer copies into the configuration of an application
function ConnectionDetails({ client }: { client: LoginClient }) {
  const { t } = useTranslation();
  const rows = [
    { label: t('loginClient.details.clientId'), value: client.clientId },
    { label: t('loginClient.details.issuer'), value: client.issuer },
    { label: t('loginClient.details.discovery'), value: `${client.issuer}/.well-known/openid-configuration` },
  ];

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Text fw={600}>{t('loginClient.details.title')}</Text>
        <Text size="sm" c="dimmed">
          {t('loginClient.details.flow')}
        </Text>
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
                    <Code>{row.value}</Code>
                    <CopyValue value={row.value} />
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        {client.consoleUrl && (
          <Text size="sm" c="dimmed">
            {t('loginClient.details.console')}{' '}
            {/* a new tab: the console has its own login, with the same account */}
            <Anchor href={client.consoleUrl} target="_blank" rel="noopener noreferrer">
              {t('loginClient.details.consoleLink')}
            </Anchor>
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

// Mantine's CopyButton passes "copy" (the click handler) and "copied" (true for a moment after the click)
// to the function inside it, which draws the button
function CopyValue({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <CopyButton value={value}>
      {({ copied, copy }) => (
        <Button variant="subtle" size="compact-xs" color={copied ? 'green' : 'gray'} onClick={copy}>
          {copied ? t('common.copied') : t('common.copy')}
        </Button>
      )}
    </CopyButton>
  );
}

function toValues(client: LoginClient): UpdateLoginClientRequest {
  return {
    redirectUris: client.redirectUris,
    postLogoutRedirectUris: client.postLogoutRedirectUris,
    webOrigins: client.webOrigins,
    defaultCatalogs: client.defaultCatalogs,
  };
}

// The settings the realm owner changes: the URIs of their applications and the default catalogs.
// The core checks the URIs (https, wildcard only at the end): its message is shown as is.
function SettingsForm({ client }: { client: LoginClient }) {
  const { t } = useTranslation();
  const catalogs = useCatalogs();
  const update = useUpdateLoginClient();

  // the form starts from the client read from the core; the client is then only read again after a save
  const form = useForm<UpdateLoginClientRequest>({ initialValues: toValues(client) });

  const submit = form.onSubmit((values) =>
    update.mutate(values, {
      // the client as saved (Keycloak may reorder the URIs) becomes the new reference: the form is no longer
      // "dirty", Cancel and Save are disabled until the next change
      onSuccess: (saved) => {
        form.setValues(toValues(saved));
        form.resetDirty(toValues(saved));
      },
    }),
  );

  return (
    <Paper withBorder p="md">
      <form onSubmit={submit}>
        <Stack>
          <Text fw={600}>{t('loginClient.settings.title')}</Text>
          {form.values.redirectUris.length === 0 && (
            <Alert color="yellow">{t('loginClient.settings.noRedirectUri')}</Alert>
          )}
          <TagsInput
            label={t('loginClient.settings.redirectUris')}
            description={t('loginClient.settings.redirectUrisDescription')}
            placeholder="https://app.example.com/*"
            {...form.getInputProps('redirectUris')}
          />
          <TagsInput
            label={t('loginClient.settings.postLogoutRedirectUris')}
            description={t('loginClient.settings.postLogoutRedirectUrisDescription')}
            placeholder="https://app.example.com/"
            {...form.getInputProps('postLogoutRedirectUris')}
          />
          <TagsInput
            label={t('loginClient.settings.webOrigins')}
            description={t('loginClient.settings.webOriginsDescription')}
            placeholder="+"
            {...form.getInputProps('webOrigins')}
          />
          <MultiSelect
            label={t('loginClient.settings.defaultCatalogs')}
            description={t('loginClient.settings.defaultCatalogsDescription')}
            data={(catalogs.data ?? []).map((catalog) => ({
              value: catalog.name,
              label: catalog.displayName ? `${catalog.displayName} (${catalog.name})` : catalog.name,
            }))}
            {...form.getInputProps('defaultCatalogs')}
          />

          {update.error && (
            <Alert color="red" title={t('common.failed')}>
              {update.error.message}
            </Alert>
          )}
          {update.isSuccess && !form.isDirty() && <Alert color="green">{t('loginClient.settings.saved')}</Alert>}

          <Group justify="flex-end">
            <Button variant="default" onClick={form.reset} disabled={!form.isDirty()}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={update.isPending} disabled={!form.isDirty()}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}
