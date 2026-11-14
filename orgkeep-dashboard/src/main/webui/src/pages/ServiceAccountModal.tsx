import { Alert, Button, Group, Modal, MultiSelect, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import {
  type ServiceAccount,
  type ServiceAccountCredentials,
  useCreateServiceAccount,
  useUpdateServiceAccount,
} from '../api/serviceAccounts';
import { isSlug } from './slug';

interface ServiceAccountModalProps {
  org: string;
  opened: boolean;
  onClose: () => void;
  // the catalogs of the org (those where it has a product): the only ones the core accepts as default catalogs
  catalogs: string[];
  // null: creation. A service account: edition of its description and default catalogs (its name cannot change)
  current: ServiceAccount | null;
  // creation only: the credentials of the new service account, to show them once
  onCreated: (credentials: ServiceAccountCredentials) => void;
}

// Creates or edits a service account. One modal for both: the form is the same, except for the name.
// The parent mounts it with a key per service account, so the form starts from the right values.
export default function ServiceAccountModal({ org, opened, onClose, catalogs, current, onCreated }: ServiceAccountModalProps) {
  const { t } = useTranslation();
  const create = useCreateServiceAccount(org);
  const update = useUpdateServiceAccount(org);
  const mutation = current ? update : create;

  const form = useForm({
    initialValues: {
      name: current?.name ?? '',
      description: current?.description ?? '',
      // a new service account of an org with a single catalog: nothing to choose, it is preselected
      defaultCatalogs: current ? current.defaultCatalogs : catalogs.length === 1 ? catalogs : [],
    },
    validate: {
      // the name is only asked at the creation
      name: (value) => (current || isSlug(value) ? null : t('serviceAccounts.form.nameInvalid')),
    },
  });

  function close() {
    form.reset();
    create.reset();
    update.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    // an empty description is sent as null: the core clears it
    const description = values.description.trim() || null;
    if (current) {
      update.mutate(
        { name: current.name, request: { description, defaultCatalogs: values.defaultCatalogs } },
        { onSuccess: close },
      );
    } else {
      create.mutate(
        { name: values.name.trim(), description, defaultCatalogs: values.defaultCatalogs },
        {
          onSuccess: (created) => {
            // the secret goes to the parent, which shows it once. Then this modal forgets it (reset in close)
            onCreated(created.credentials);
            close();
          },
        },
      );
    }
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={current ? t('serviceAccounts.form.editTitle', { name: current.name }) : t('serviceAccounts.form.createTitle', { org })}
    >
      <form onSubmit={submit}>
        <Stack>
          {!current && (
            <TextInput
              label={t('serviceAccounts.form.name')}
              description={t('serviceAccounts.form.nameDescription', { org })}
              placeholder="billing"
              withAsterisk
              {...form.getInputProps('name')}
            />
          )}
          <TextInput label={t('serviceAccounts.form.description')} {...form.getInputProps('description')} />
          <MultiSelect
            label={t('serviceAccounts.form.defaultCatalogs')}
            description={t('serviceAccounts.form.defaultCatalogsDescription')}
            data={catalogs}
            {...form.getInputProps('defaultCatalogs')}
          />
          {catalogs.length === 0 && (
            <Text size="sm" c="dimmed">
              {t('serviceAccounts.form.noCatalog')}
            </Text>
          )}

          {mutation.error && (
            <Alert color="red" title={t('common.failed')}>
              {mutation.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={mutation.isPending}>
              {current ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
