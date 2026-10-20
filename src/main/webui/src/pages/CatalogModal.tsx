import { Alert, Button, Group, Modal, Stack, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type Catalog, useCreateCatalog, useUpdateCatalog } from '../api/catalogs';
import { isSlug } from './slug';

interface CatalogModalProps {
  opened: boolean;
  onClose: () => void;
  // set: "Edit" of this catalog, whose name cannot change. Null: "New catalog"
  current: Catalog | null;
  // called with the created or updated catalog, e.g. to open its page
  onSaved?: (catalog: Catalog) => void;
}

interface FormValues {
  name: string;
  displayName: string;
  description: string;
}

// '' -> null: the core stores no description rather than an empty one
function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

// Creates a catalog, or edits the display name and the description of one: the same form, two calls of the core.
export default function CatalogModal({ opened, onClose, current, onSaved }: CatalogModalProps) {
  const { t } = useTranslation();
  const createCatalog = useCreateCatalog();
  // the hook needs a catalog name: '' while creating, the mutation is then never called
  const updateCatalog = useUpdateCatalog(current?.name ?? '');
  // the call this form makes: its pending state and its error are shown below
  const mutation = current ? updateCatalog : createCatalog;

  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each catalog (see CatalogPage)
    initialValues: {
      name: current?.name ?? '',
      displayName: current?.displayName ?? '',
      description: current?.description ?? '',
    },
    validate: {
      // the name is only typed when creating
      name: (value) => (current || isSlug(value) ? null : t('catalogForm.nameInvalid')),
      displayName: (value) => (value.trim() !== '' ? null : t('catalogForm.displayNameRequired')),
    },
  });

  function close() {
    // the next opening starts from the initial values, without the error of the previous attempt
    form.reset();
    mutation.reset();
    onClose();
  }

  function saved(catalog: Catalog) {
    close();
    onSaved?.(catalog);
  }

  const submit = form.onSubmit((values) => {
    const displayName = values.displayName.trim();
    const description = emptyToNull(values.description);
    if (current) {
      updateCatalog.mutate({ displayName, description }, { onSuccess: saved });
    } else {
      createCatalog.mutate({ name: values.name.trim(), displayName, description }, { onSuccess: saved });
    }
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={current ? t('catalogForm.editTitle', { catalog: current.name }) : t('catalogForm.createTitle')}
    >
      <form onSubmit={submit}>
        <Stack>
          {/* the name is the identifier: typed once, never edited */}
          {!current && (
            <TextInput
              label={t('catalogForm.name')}
              description={t('catalogForm.nameDescription')}
              placeholder="sandbox"
              withAsterisk
              data-autofocus
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('name')}
            />
          )}
          <TextInput
            label={t('catalogForm.displayName')}
            placeholder="Sandbox"
            withAsterisk
            {...form.getInputProps('displayName')}
          />
          <Textarea label={t('catalogForm.description')} autosize minRows={2} {...form.getInputProps('description')} />

          {/* the error of the core, e.g. 409 "name already taken": the modal stays open to fix the values */}
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
