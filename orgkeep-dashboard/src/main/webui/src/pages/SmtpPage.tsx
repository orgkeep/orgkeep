import {
  Alert,
  Button,
  Center,
  Group,
  Loader,
  NumberInput,
  Paper,
  PasswordInput,
  Stack,
  Switch,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { type SmtpServer, type UpdateSmtpRequest, useDeleteSmtp, useSmtp, useUpdateSmtp } from '../api/smtp';
import type { UseMutationResult } from '@tanstack/react-query';
import ConfirmModal from '../components/ConfirmModal';

// The SMTP server of the realm (realm owner): what Keycloak sends invitations, password resets and email
// verifications through. Read-only while it is set by the configuration of Orgkeep (ORGKEEP_SMTP_HOST).
export default function SmtpPage() {
  const { t } = useTranslation();
  const { data: smtp, isPending, error } = useSmtp();
  // owned here, not by SmtpForm: the form remounts (see the key below) on every save that changes the host, which
  // would otherwise create a fresh mutation and lose isSuccess right when the "Saved" alert needs it
  const update = useUpdateSmtp();

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('smtp.loadError')} maw={700}>
        {error.message}
      </Alert>
    );
  }

  return (
    <Stack maw={700}>
      <div>
        <Title order={2}>{t('smtp.title')}</Title>
        <Text c="dimmed">{t('smtp.subtitle')}</Text>
      </div>
      {smtp.managedByConfiguration && <Alert color="blue">{t('smtp.managedByConfiguration')}</Alert>}
      {!smtp.managedByConfiguration && smtp.host === null && <Alert color="yellow">{t('smtp.noServer')}</Alert>}
      {/* key: the form reads its initial values once, and starts over if the server changes underneath it */}
      <SmtpForm key={`${smtp.host}-${smtp.managedByConfiguration}`} smtp={smtp} update={update} />
    </Stack>
  );
}

// The values of the form. port is '' while the field is empty (NumberInput), never sent as is: the core requires
// a number. password starts empty: left empty, it is left out of the request (the core keeps the stored password).
interface FormValues {
  host: string;
  port: number | '';
  from: string;
  fromDisplayName: string;
  starttls: boolean;
  ssl: boolean;
  username: string;
  password: string;
}

function toValues(smtp: SmtpServer): FormValues {
  return {
    host: smtp.host ?? '',
    port: smtp.port ?? 25,
    from: smtp.from ?? '',
    fromDisplayName: smtp.fromDisplayName ?? '',
    starttls: smtp.starttls,
    ssl: smtp.ssl,
    username: smtp.username ?? '',
    password: '',
  };
}

function SmtpForm({
  smtp,
  update,
}: {
  smtp: SmtpServer;
  update: UseMutationResult<SmtpServer, Error, UpdateSmtpRequest>;
}) {
  const { t } = useTranslation();
  const deleteSmtp = useDeleteSmtp();
  const [confirmOpened, { open: openConfirm, close: closeConfirm }] = useDisclosure();
  const disabled = smtp.managedByConfiguration;

  const form = useForm<FormValues>({
    initialValues: toValues(smtp),
    validate: {
      host: (value) => (value.trim() === '' ? t('common.required') : null),
      port: (value) => (value === '' ? t('common.required') : null),
      from: (value) => (value.trim() === '' ? t('common.required') : null),
    },
  });

  const submit = form.onSubmit((values) => {
    const request: UpdateSmtpRequest = {
      host: values.host.trim(),
      port: Number(values.port),
      from: values.from.trim(),
      fromDisplayName: values.fromDisplayName.trim(),
      username: values.username.trim(),
      starttls: values.starttls,
      ssl: values.ssl,
      // only sent when typed: an empty value here must not replace the stored password
      ...(values.password.trim() !== '' ? { password: values.password } : {}),
    };
    update.mutate(request, {
      // the server as saved becomes the new reference, password field back to empty
      onSuccess: (saved) => {
        form.setValues(toValues(saved));
        form.resetDirty(toValues(saved));
      },
    });
  });

  function closeRemoveConfirm() {
    deleteSmtp.reset();
    closeConfirm();
  }

  return (
    <Paper withBorder p="md">
      <form onSubmit={submit}>
        <Stack>
          <TextInput
            label={t('smtp.form.host')}
            withAsterisk
            disabled={disabled}
            {...form.getInputProps('host')}
          />
          <NumberInput
            label={t('smtp.form.port')}
            withAsterisk
            disabled={disabled}
            min={1}
            max={65535}
            allowDecimal={false}
            allowNegative={false}
            {...form.getInputProps('port')}
          />
          <TextInput
            label={t('smtp.form.from')}
            withAsterisk
            disabled={disabled}
            {...form.getInputProps('from')}
          />
          <TextInput
            label={t('smtp.form.fromDisplayName')}
            disabled={disabled}
            {...form.getInputProps('fromDisplayName')}
          />
          <Group>
            <Switch
              label={t('smtp.form.starttls')}
              disabled={disabled}
              checked={form.values.starttls}
              onChange={(event) => form.setFieldValue('starttls', event.currentTarget.checked)}
            />
            <Switch
              label={t('smtp.form.ssl')}
              disabled={disabled}
              checked={form.values.ssl}
              onChange={(event) => form.setFieldValue('ssl', event.currentTarget.checked)}
            />
          </Group>
          <TextInput
            label={t('smtp.form.username')}
            description={t('smtp.form.usernameDescription')}
            disabled={disabled}
            {...form.getInputProps('username')}
          />
          <PasswordInput
            label={t('smtp.form.password')}
            placeholder={smtp.passwordSet ? t('smtp.form.passwordPlaceholder') : undefined}
            description={smtp.passwordSet ? t('smtp.form.passwordDescription') : t('smtp.form.passwordDescriptionNone')}
            disabled={disabled}
            {...form.getInputProps('password')}
          />

          {update.error && (
            <Alert color="red" title={t('common.failed')}>
              {update.error.message}
            </Alert>
          )}
          {update.isSuccess && !form.isDirty() && <Alert color="green">{t('smtp.saved')}</Alert>}

          {!disabled && (
            <Group justify={smtp.host !== null ? 'space-between' : 'flex-end'}>
              {smtp.host !== null && (
                <Button color="red" variant="outline" onClick={openConfirm}>
                  {t('smtp.remove.button')}
                </Button>
              )}
              <Group>
                <Button variant="default" onClick={form.reset} disabled={!form.isDirty()}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" loading={update.isPending} disabled={!form.isDirty()}>
                  {t('common.save')}
                </Button>
              </Group>
            </Group>
          )}
        </Stack>
      </form>

      <ConfirmModal
        opened={confirmOpened}
        title={t('smtp.remove.confirm.title')}
        message={t('smtp.remove.confirm.message')}
        confirmLabel={t('smtp.remove.confirm.button')}
        pending={deleteSmtp.isPending}
        error={deleteSmtp.error}
        onConfirm={() =>
          deleteSmtp.mutate(undefined, {
            // also clears any earlier "Saved" from update: the form that reappears here (no host) did not just save
            onSuccess: () => {
              update.reset();
              closeConfirm();
            },
          })
        }
        onClose={closeRemoveConfirm}
      />
    </Paper>
  );
}
