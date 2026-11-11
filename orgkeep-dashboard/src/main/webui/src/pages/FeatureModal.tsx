import { Alert, Button, Checkbox, Group, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { usePutFeature } from '../api/catalogs';
import type { Feature } from '../api/products';
import { isSlug } from './slug';

interface FeatureModalProps {
  catalog: string;
  product: string;
  opened: boolean;
  onClose: () => void;
  // set: "Edit" of this feature, whose name and "metered" cannot change. Null: "Add a feature"
  current: Feature | null;
}

interface FormValues {
  name: string;
  displayName: string;
  metered: boolean;
}

// Adds a feature to a product, or changes the display name of one: a single PUT in the core for both.
// Its 409 is shown as it is: a first metered feature on a product already assigned to organisations, for example.
export default function FeatureModal({ catalog, product, opened, onClose, current }: FeatureModalProps) {
  const { t } = useTranslation();
  const putFeature = usePutFeature(catalog, product);

  const form = useForm<FormValues>({
    // read once: the parent gives the modal a new key for each feature
    initialValues: {
      name: current?.name ?? '',
      displayName: current?.displayName ?? '',
      metered: current?.metered ?? false,
    },
    validate: {
      name: (value) => (current || isSlug(value) ? null : t('productForm.nameInvalid')),
    },
  });

  function close() {
    form.reset();
    putFeature.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    const displayName = values.displayName.trim() === '' ? null : values.displayName.trim();
    // for an existing feature, metered is sent unchanged: the core only renames it
    putFeature.mutate({ feature: values.name.trim(), displayName, metered: values.metered }, { onSuccess: close });
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={
        current ? t('productDetail.features.editTitle', { feature: current.name }) : t('productDetail.features.addTitle')
      }
    >
      <form onSubmit={submit}>
        <Stack>
          {!current && (
            <TextInput
              label={t('productForm.features.name')}
              description={t('productDetail.features.nameDescription', { product })}
              placeholder="read"
              withAsterisk
              data-autofocus
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('name')}
            />
          )}
          <TextInput
            label={t('productForm.features.displayName')}
            placeholder={t('productForm.features.displayNamePlaceholder')}
            {...form.getInputProps('displayName')}
          />
          {/* chosen when the feature is created, then fixed: delete and recreate it to change it */}
          <Checkbox
            label={t('productForm.features.metered')}
            description={current ? t('productDetail.features.meteredFixed') : t('productForm.features.meteredHint')}
            disabled={current !== null}
            {...form.getInputProps('metered', { type: 'checkbox' })}
          />

          {putFeature.error && (
            <Alert color="red" title={t('common.failed')}>
              {putFeature.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={putFeature.isPending}>
              {current ? t('common.save') : t('productDetail.features.add')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
