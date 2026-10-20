import {
  Alert,
  Button,
  Checkbox,
  Fieldset,
  Group,
  Modal,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  TagsInput,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { type UseFormReturnType, useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { useCreateProduct, useUpdateProduct } from '../api/catalogs';
import type { ParamDefinition, ParamType, Product } from '../api/products';
import { isSlug } from './slug';

interface ProductModalProps {
  catalog: string;
  opened: boolean;
  onClose: () => void;
  // set: "Edit" of this product (display name, description, parameters). Null: "New product"
  current: Product | null;
  onSaved?: (product: Product) => void;
}

// A row of a list of the form. "key" is only for React (key={...} of the row), never sent to the backend:
// the index would not do, it shifts when a row in the middle is removed.
interface RoleRow {
  key: string;
  name: string;
  displayName: string;
}

interface ParamRow {
  key: string;
  name: string;
  type: ParamType;
  required: boolean;
  // '' = no default
  defaultValue: string;
  // the allowed values, for an ENUM only
  values: string[];
}

interface FormValues {
  name: string;
  displayName: string;
  description: string;
  // only when creating: the roles of an existing product are changed one by one, on its page
  roles: RoleRow[];
  params: ParamRow[];
}

const PARAM_TYPES: ParamType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'ENUM'];

function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

function toParamRow(param: ParamDefinition): ParamRow {
  return { key: randomId(), ...param, defaultValue: param.defaultValue ?? '' };
}

function toParamDefinition(row: ParamRow): ParamDefinition {
  return {
    name: row.name.trim(),
    type: row.type,
    required: row.required,
    defaultValue: emptyToNull(row.defaultValue),
    // the values only mean something for an enum: the core refuses them on another type
    values: row.type === 'ENUM' ? row.values : [],
  };
}

// Creates a product with its roles and its parameter schema, or edits a product.
export default function ProductModal({ catalog, opened, onClose, current, onSaved }: ProductModalProps) {
  const { t } = useTranslation();
  const createProduct = useCreateProduct(catalog);
  const updateProduct = useUpdateProduct(catalog, current?.name ?? '');
  const mutation = current ? updateProduct : createProduct;

  const form = useForm<FormValues>({
    // read once: the parent gives the modal a new key for each product
    initialValues: {
      name: current?.name ?? '',
      displayName: current?.displayName ?? '',
      description: current?.description ?? '',
      roles: [],
      params: current?.params.map(toParamRow) ?? [],
    },
    // Rules of a list: one rule per field, applied to every row (roles.0.name, roles.1.name...)
    validate: {
      name: (value) => (current || isSlug(value) ? null : t('productForm.nameInvalid')),
      displayName: (value) => (value.trim() !== '' ? null : t('productForm.displayNameRequired')),
      roles: {
        name: (value) => (isSlug(value) ? null : t('productForm.nameInvalid')),
      },
      params: {
        name: (value) => (isSlug(value) ? null : t('productForm.nameInvalid')),
        values: (value, values, path) => {
          // path is "params.2.values": the type is read in the same row
          const index = Number(path.split('.')[1]);
          return values.params[index].type === 'ENUM' && value.length === 0 ? t('productForm.params.valuesRequired') : null;
        },
      },
    },
  });

  function close() {
    form.reset();
    mutation.reset();
    onClose();
  }

  function saved(product: Product) {
    close();
    onSaved?.(product);
  }

  const submit = form.onSubmit((values) => {
    const displayName = values.displayName.trim();
    const description = emptyToNull(values.description);
    const params = values.params.map(toParamDefinition);
    if (current) {
      updateProduct.mutate({ displayName, description, params }, { onSuccess: saved });
    } else {
      const roles = values.roles.map((row) => ({ name: row.name.trim(), displayName: emptyToNull(row.displayName) }));
      createProduct.mutate({ name: values.name.trim(), displayName, description, roles, params }, { onSuccess: saved });
    }
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      size="xl"
      title={
        current
          ? t('productForm.editTitle', { product: `${catalog}/${current.name}` })
          : t('productForm.createTitle', { catalog })
      }
    >
      <form onSubmit={submit}>
        <Stack>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {!current && (
              <TextInput
                label={t('productForm.name')}
                description={t('productForm.nameDescription')}
                placeholder="billing"
                withAsterisk
                data-autofocus
                styles={{ input: { fontFamily: 'monospace' } }}
                {...form.getInputProps('name')}
              />
            )}
            <TextInput
              label={t('productForm.displayName')}
              placeholder="Billing API"
              withAsterisk
              {...form.getInputProps('displayName')}
            />
          </SimpleGrid>
          <Textarea label={t('productForm.description')} autosize minRows={2} {...form.getInputProps('description')} />

          {!current && <RolesFieldset form={form} />}
          <ParamsFieldset form={form} />

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

// The form is passed down to the sub components: they read and change the same values, like a form-backing
// bean shared by several fragments of a page.
type ProductForm = UseFormReturnType<FormValues>;

// The roles of a new product: one row per role, added and removed freely
function RolesFieldset({ form }: { form: ProductForm }) {
  const { t } = useTranslation();

  return (
    <Fieldset legend={t('productForm.roles.legend')}>
      <Stack gap="sm">
        <Text size="xs" c="dimmed">
          {t('productForm.roles.description')}
        </Text>
        {form.values.roles.map((role, index) => (
          <Group key={role.key} align="flex-start" wrap="nowrap">
            <TextInput
              aria-label={t('productForm.roles.name')}
              placeholder="read"
              styles={{ input: { fontFamily: 'monospace' } }}
              style={{ flex: 1 }}
              {...form.getInputProps(`roles.${index}.name`)}
            />
            <TextInput
              aria-label={t('productForm.roles.displayName')}
              placeholder={t('productForm.roles.displayNamePlaceholder')}
              style={{ flex: 1 }}
              {...form.getInputProps(`roles.${index}.displayName`)}
            />
            <Button variant="subtle" color="red" onClick={() => form.removeListItem('roles', index)}>
              {t('common.remove')}
            </Button>
          </Group>
        ))}
        <Group>
          <Button
            variant="light"
            size="xs"
            onClick={() => form.insertListItem('roles', { key: randomId(), name: '', displayName: '' })}
          >
            {t('productForm.roles.add')}
          </Button>
        </Group>
      </Stack>
    </Fieldset>
  );
}

// The parameter schema: what each organisation negotiates besides its quota (e.g. a region)
function ParamsFieldset({ form }: { form: ProductForm }) {
  const { t } = useTranslation();

  function changeType(index: number, type: string | null) {
    form.setFieldValue(`params.${index}.type`, (type ?? 'STRING') as ParamType);
    // a default of the previous type would mean nothing for the new one
    form.setFieldValue(`params.${index}.defaultValue`, '');
  }

  return (
    <Fieldset legend={t('productForm.params.legend')}>
      <Stack gap="sm">
        <Text size="xs" c="dimmed">
          {t('productForm.params.description')}
        </Text>
        {form.values.params.map((param, index) => (
          <Paper key={param.key} withBorder p="sm">
            <Stack gap="xs">
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <TextInput
                  label={t('productForm.params.name')}
                  placeholder="region"
                  withAsterisk
                  styles={{ input: { fontFamily: 'monospace' } }}
                  {...form.getInputProps(`params.${index}.name`)}
                />
                <Select
                  label={t('productForm.params.type')}
                  data={PARAM_TYPES.map((type) => ({ value: type, label: t(`productForm.params.types.${type}`) }))}
                  allowDeselect={false}
                  value={param.type}
                  onChange={(value) => changeType(index, value)}
                />
                <DefaultValueField form={form} index={index} />
              </SimpleGrid>
              {param.type === 'ENUM' && (
                <TagsInput
                  label={t('productForm.params.values')}
                  description={t('productForm.params.valuesDescription')}
                  placeholder="eu"
                  withAsterisk
                  splitChars={[',', ' ']}
                  {...form.getInputProps(`params.${index}.values`)}
                />
              )}
              <Group justify="space-between">
                <Checkbox
                  label={t('productForm.params.required')}
                  {...form.getInputProps(`params.${index}.required`, { type: 'checkbox' })}
                />
                <Button variant="subtle" color="red" size="xs" onClick={() => form.removeListItem('params', index)}>
                  {t('common.remove')}
                </Button>
              </Group>
            </Stack>
          </Paper>
        ))}
        <Group>
          <Button
            variant="light"
            size="xs"
            onClick={() =>
              form.insertListItem('params', {
                key: randomId(),
                name: '',
                type: 'STRING',
                required: false,
                defaultValue: '',
                values: [],
              })
            }
          >
            {t('productForm.params.add')}
          </Button>
        </Group>
      </Stack>
    </Fieldset>
  );
}

// The default of a parameter, typed from its type: a list for an enum or a boolean, free text otherwise.
// The core checks the value against the type again.
function DefaultValueField({ form, index }: { form: ProductForm; index: number }) {
  const { t } = useTranslation();
  const param = form.values.params[index];
  const path = `params.${index}.defaultValue`;
  const common = { label: t('productForm.params.defaultValue'), placeholder: t('productForm.params.noDefault') };

  switch (param.type) {
    case 'ENUM':
      return (
        <Select
          {...common}
          data={param.values}
          clearable
          value={param.defaultValue || null}
          onChange={(value) => form.setFieldValue(path, value ?? '')}
        />
      );
    case 'BOOLEAN':
      return (
        <Select
          {...common}
          data={[
            { value: 'true', label: t('productForm.params.true') },
            { value: 'false', label: t('productForm.params.false') },
          ]}
          clearable
          value={param.defaultValue || null}
          onChange={(value) => form.setFieldValue(path, value ?? '')}
        />
      );
    default:
      return <TextInput {...common} inputMode={param.type === 'NUMBER' ? 'decimal' : undefined} {...form.getInputProps(path)} />;
  }
}
