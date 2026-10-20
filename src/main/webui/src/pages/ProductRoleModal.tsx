import { Alert, Button, Group, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { usePutProductRole } from '../api/catalogs';
import type { ProductRole } from '../api/products';
import { isSlug } from './slug';

interface ProductRoleModalProps {
  catalog: string;
  product: string;
  opened: boolean;
  onClose: () => void;
  // set: "Edit" of this role, whose name cannot change. Null: "Add a role"
  current: ProductRole | null;
}

interface FormValues {
  name: string;
  displayName: string;
}

// Adds a role to a product, or changes the display name of one: a single PUT in the core for both.
export default function ProductRoleModal({ catalog, product, opened, onClose, current }: ProductRoleModalProps) {
  const { t } = useTranslation();
  const putRole = usePutProductRole(catalog, product);

  const form = useForm<FormValues>({
    // read once: the parent gives the modal a new key for each role
    initialValues: { name: current?.name ?? '', displayName: current?.displayName ?? '' },
    validate: {
      name: (value) => (current || isSlug(value) ? null : t('productForm.nameInvalid')),
    },
  });

  function close() {
    form.reset();
    putRole.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    const displayName = values.displayName.trim() === '' ? null : values.displayName.trim();
    putRole.mutate({ role: values.name.trim(), displayName }, { onSuccess: close });
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={current ? t('productDetail.roles.editTitle', { role: current.name }) : t('productDetail.roles.addTitle')}
    >
      <form onSubmit={submit}>
        <Stack>
          {!current && (
            <TextInput
              label={t('productForm.roles.name')}
              description={t('productDetail.roles.nameDescription', { product })}
              placeholder="read"
              withAsterisk
              data-autofocus
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('name')}
            />
          )}
          <TextInput
            label={t('productForm.roles.displayName')}
            placeholder={t('productForm.roles.displayNamePlaceholder')}
            {...form.getInputProps('displayName')}
          />

          {putRole.error && (
            <Alert color="red" title={t('common.failed')}>
              {putRole.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={putRole.isPending}>
              {current ? t('common.save') : t('productDetail.roles.add')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
