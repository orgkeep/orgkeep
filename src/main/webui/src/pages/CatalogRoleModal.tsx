import { Alert, Button, Group, Modal, Stack, Switch, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type CatalogRole, useCreateCatalogRole, useUpdateCatalogRole } from '../api/catalogs';
import { isSlug } from './slug';

interface CatalogRoleModalProps {
  catalog: string;
  opened: boolean;
  onClose: () => void;
  // set: "Edit" of this role, whose name cannot change. Null: "New role"
  current: CatalogRole | null;
  // called with the created or updated role, e.g. to open its page
  onSaved?: (role: CatalogRole) => void;
}

interface FormValues {
  name: string;
  displayName: string;
  description: string;
  orgGrantable: boolean;
}

// '' -> null: the core stores no display name or description rather than an empty one
function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

// Creates a catalog role, or edits one: the same form, two calls of the core (like CatalogModal).
export default function CatalogRoleModal({ catalog, opened, onClose, current, onSaved }: CatalogRoleModalProps) {
  const { t } = useTranslation();
  const createRole = useCreateCatalogRole(catalog);
  // the hook needs a role name: '' while creating, the mutation is then never called
  const updateRole = useUpdateCatalogRole(catalog, current?.name ?? '');
  const mutation = current ? updateRole : createRole;

  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each role (see CatalogRolePage)
    initialValues: {
      name: current?.name ?? '',
      displayName: current?.displayName ?? '',
      description: current?.description ?? '',
      orgGrantable: current?.orgGrantable ?? true,
    },
    validate: {
      // the name is only typed when creating
      name: (value) => (current || isSlug(value) ? null : t('catalogRoleForm.nameInvalid')),
    },
  });

  function close() {
    form.reset();
    mutation.reset();
    onClose();
  }

  function saved(role: CatalogRole) {
    close();
    onSaved?.(role);
  }

  const submit = form.onSubmit((values) => {
    const fields = {
      displayName: emptyToNull(values.displayName),
      description: emptyToNull(values.description),
      orgGrantable: values.orgGrantable,
    };
    if (current) {
      updateRole.mutate(fields, { onSuccess: saved });
    } else {
      createRole.mutate({ name: values.name.trim(), ...fields }, { onSuccess: saved });
    }
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={current ? t('catalogRoleForm.editTitle', { role: current.name }) : t('catalogRoleForm.createTitle')}
    >
      <form onSubmit={submit}>
        <Stack>
          {!current && (
            <TextInput
              label={t('catalogRoleForm.name')}
              description={t('catalogRoleForm.nameDescription')}
              placeholder="searcher"
              withAsterisk
              data-autofocus
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('name')}
            />
          )}
          <TextInput label={t('catalogRoleForm.displayName')} placeholder="Searcher" {...form.getInputProps('displayName')} />
          <Textarea label={t('catalogRoleForm.description')} autosize minRows={2} {...form.getInputProps('description')} />
          <Switch
            label={t('catalogRoleForm.orgGrantable')}
            // a reserved role made grantable is harmless; the opposite removes it from the teams of every org
            description={
              current?.orgGrantable
                ? t('catalogRoleForm.orgGrantableWarning')
                : t('catalogRoleForm.orgGrantableDescription')
            }
            // a checkbox-like input: getInputProps needs to know it reads "checked", not "value"
            {...form.getInputProps('orgGrantable', { type: 'checkbox' })}
          />

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
