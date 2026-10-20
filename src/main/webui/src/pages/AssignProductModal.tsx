import {
  Alert,
  Button,
  Code,
  Group,
  Input,
  Modal,
  MultiSelect,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type OrgProduct, type QuotaCycle, useAssignProduct } from '../api/organisations';
import { type ParamDefinition, type Product, productKey, productOptions, splitProductKey } from '../api/products';

interface AssignProductModalProps {
  org: string;
  opened: boolean;
  onClose: () => void;
  // every product of every catalog: the list to choose from, the roles and the parameters of the chosen one
  products: Product[];
  // the products already assigned: left out of the list when assigning a new one
  assigned: OrgProduct[];
  // set: "Edit" of this assignment, whose product cannot be changed. Null: "Assign a product"
  current: OrgProduct | null;
}

interface FormValues {
  // "catalog/product", see productKey
  productKey: string | null;
  // NumberInput gives '' while the field is empty, a number otherwise
  quota: number | string;
  cycle: QuotaCycle;
  boughtRoles: string[];
  // one entry per parameter, '' when the field is empty
  params: Record<string, string>;
}

const CYCLES: QuotaCycle[] = ['DAILY', 'MONTHLY', 'YEARLY'];

// Assigns a product to the organisation with what it negotiated (quota, bought roles, parameters), or edits an
// assignment: the core has a single PUT for both (assigning again replaces the values).
export default function AssignProductModal({ org, opened, onClose, products, assigned, current }: AssignProductModalProps) {
  const { t } = useTranslation();
  const assignProduct = useAssignProduct(org);

  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each assignment (see OrganisationPage)
    initialValues: {
      productKey: current ? productKey(current.catalog, current.product) : null,
      quota: current?.quota ?? '',
      cycle: current?.cycle ?? 'MONTHLY',
      boughtRoles: current?.boughtRoles ?? [],
      params: current?.params ?? {},
    },
    validate: {
      productKey: (value) => (value ? null : t('orgDetail.assign.productRequired')),
      // same rule as the core (@Positive), checked here to show it while typing
      quota: (value) => (typeof value === 'number' && value > 0 ? null : t('orgDetail.assign.quotaInvalid')),
    },
  });

  // the product chosen in the form: its roles and its parameters drive the rest of the form
  const product = products.find((p) => productKey(p.catalog, p.name) === form.values.productKey) ?? null;

  const assignedKeys = assigned.map((a) => productKey(a.catalog, a.product));
  // a new assignment only offers the products the org does not have yet
  const options = productOptions(products.filter((p) => current !== null || !assignedKeys.includes(productKey(p.catalog, p.name))));

  function changeProduct(key: string | null) {
    // the roles and parameters of the previous product mean nothing for the new one
    form.setValues({ productKey: key, boughtRoles: [], params: {} });
  }

  function close() {
    form.reset();
    assignProduct.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    // the validation above guarantees a product and a number
    const { catalog, name } = splitProductKey(values.productKey!);
    // an empty field is left out: the core applies the default of the parameter
    const params = Object.fromEntries(Object.entries(values.params).filter(([, value]) => value !== ''));
    assignProduct.mutate(
      {
        catalog,
        product: name,
        request: { quota: Number(values.quota), cycle: values.cycle, boughtRoles: values.boughtRoles, params },
      },
      { onSuccess: close },
    );
  });

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={
        current
          ? t('orgDetail.assign.editTitle', { product: productKey(current.catalog, current.product) })
          : t('orgDetail.assign.title', { org })
      }
    >
      <form onSubmit={submit}>
        <Stack>
          <Select
            label={t('orgDetail.assign.product')}
            placeholder={t('orgDetail.assign.productPlaceholder')}
            data={options}
            disabled={current !== null}
            withAsterisk
            searchable
            nothingFoundMessage={t('orgDetail.assign.noProductLeft')}
            value={form.values.productKey}
            onChange={changeProduct}
            error={form.errors.productKey}
          />
          <NumberInput
            label={t('orgDetail.assign.quota')}
            placeholder="1000"
            min={1}
            // whole numbers only: the core stores a long
            allowDecimal={false}
            allowNegative={false}
            thousandSeparator=" "
            withAsterisk
            {...form.getInputProps('quota')}
          />
          {/* Input.Wrapper adds the label above a component that has none, like SegmentedControl */}
          <Input.Wrapper label={t('orgDetail.assign.cycle')} withAsterisk>
            <SegmentedControl
              fullWidth
              data={CYCLES.map((cycle) => ({ value: cycle, label: t(`orgDetail.cycles.${cycle}`) }))}
              {...form.getInputProps('cycle')}
            />
          </Input.Wrapper>

          {/* only for a product that has roles: the ceiling of what the teams of the org can give */}
          {product && product.roles.length > 0 && (
            <MultiSelect
              label={t('orgDetail.assign.boughtRoles')}
              description={t('orgDetail.assign.boughtRolesDescription')}
              data={product.roles.map((role) => ({ value: role.name, label: role.displayName ?? role.name }))}
              {...form.getInputProps('boughtRoles')}
            />
          )}

          {product?.params.map((param) => (
            <ParamField
              key={param.name}
              param={param}
              value={form.values.params[param.name] ?? ''}
              onChange={(value) => form.setFieldValue('params', { ...form.values.params, [param.name]: value })}
            />
          ))}

          {product && (
            <Text size="sm" c="dimmed">
              {t('orgDetail.assign.tokenHint')} <Code>resource_access.{product.catalog}</Code>{' '}
              <Code>
                {product.catalog}.{product.name}.quotas
              </Code>
            </Text>
          )}

          {assignProduct.error && (
            <Alert color="red" title={t('orgDetail.assign.failed')}>
              {assignProduct.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={assignProduct.isPending}>
              {current ? t('orgDetail.assign.save') : t('orgDetail.assign.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

interface ParamFieldProps {
  param: ParamDefinition;
  // '' when empty
  value: string;
  onChange: (value: string) => void;
}

// One field per parameter, chosen from its type. The core checks the value again: this only helps to type it.
function ParamField({ param, value, onChange }: ParamFieldProps) {
  const { t } = useTranslation();
  // an empty field takes the default: shown as the placeholder, not filled in, so that it stays a default
  const placeholder = param.defaultValue ? t('orgDetail.assign.paramDefault', { value: param.defaultValue }) : undefined;
  // required without default: the org must give a value
  const withAsterisk = param.required && !param.defaultValue;
  const common = { label: param.name, placeholder, withAsterisk };

  switch (param.type) {
    case 'ENUM':
      return <Select {...common} data={param.values} value={value || null} onChange={(v) => onChange(v ?? '')} clearable />;
    case 'BOOLEAN':
      return (
        <Select
          {...common}
          data={[
            { value: 'true', label: t('orgDetail.assign.true') },
            { value: 'false', label: t('orgDetail.assign.false') },
          ]}
          value={value || null}
          onChange={(v) => onChange(v ?? '')}
          clearable
        />
      );
    case 'NUMBER':
      // NumberInput gives a number, or '' when empty: the request carries strings
      return <NumberInput {...common} value={value} onChange={(v) => onChange(v === '' ? '' : String(v))} />;
    default:
      return <TextInput {...common} value={value} onChange={(event) => onChange(event.currentTarget.value)} />;
  }
}
