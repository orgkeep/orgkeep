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
import { type FeatureQuota, type OrgProduct, type QuotaCycle, useAssignProduct } from '../api/organisations';
import { type ParamDefinition, type Product, productKey, productOptions, splitProductKey } from '../api/products';

interface AssignProductModalProps {
  org: string;
  opened: boolean;
  onClose: () => void;
  // every product of every catalog: the list to choose from, the features and the parameters of the chosen one
  products: Product[];
  // the products already assigned: left out of the list when assigning a new one
  assigned: OrgProduct[];
  // set: "Edit" of this assignment, whose product cannot be changed. Null: "Assign a product"
  current: OrgProduct | null;
}

// The quota of a metered feature while it is typed: NumberInput gives '' while the field is empty
interface FeatureQuotaRow {
  quota: number | string;
  cycle: QuotaCycle;
}

interface FormValues {
  // "catalog/product", see productKey
  productKey: string | null;
  // simple product only. NumberInput gives '' while the field is empty, a number otherwise
  quota: number | string;
  cycle: QuotaCycle;
  boughtFeatures: string[];
  // metered product only: by feature name. A feature taken out of boughtFeatures keeps its row here, so that its
  // values come back if it is selected again; only the bought metered features are sent
  featureQuotas: Record<string, FeatureQuotaRow>;
  // one entry per parameter, '' when the field is empty
  params: Record<string, string>;
}

const CYCLES: QuotaCycle[] = ['DAILY', 'MONTHLY', 'YEARLY'];

function isPositiveNumber(value: number | string): boolean {
  return typeof value === 'number' && value > 0;
}

// The metered features of the product that the form says are bought: one quota row each
function boughtMeteredFeatures(product: Product, boughtFeatures: string[]): string[] {
  return product.features
    .filter((feature) => feature.metered && boughtFeatures.includes(feature.name))
    .map((feature) => feature.name);
}

// Assigns a product to the organisation with what it negotiated (features bought, quotas, parameters), or edits an
// assignment: the core has a single PUT for both (assigning again replaces the values).
// The quotas follow the kind of the product: a simple product has one quota, a metered product one per metered
// feature bought (the core checks it again and answers 400).
export default function AssignProductModal({ org, opened, onClose, products, assigned, current }: AssignProductModalProps) {
  const { t } = useTranslation();
  const assignProduct = useAssignProduct(org);

  function findProduct(key: string | null): Product | null {
    return products.find((p) => productKey(p.catalog, p.name) === key) ?? null;
  }

  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each assignment (see OrganisationPage)
    initialValues: {
      productKey: current ? productKey(current.catalog, current.product) : null,
      quota: current?.quota ?? '',
      cycle: current?.cycle ?? 'MONTHLY',
      boughtFeatures: current?.boughtFeatures ?? [],
      featureQuotas: current?.featureQuotas ?? {},
      params: current?.params ?? {},
    },
    // A function rather than one rule per field: what is required depends on the kind of the chosen product.
    // The keys of the errors are the paths of the fields ("featureQuotas.scan.quota"), as getInputProps reads them.
    validate: (values) => {
      const errors: Record<string, string> = {};
      const chosen = findProduct(values.productKey);
      if (!chosen) {
        errors.productKey = t('orgDetail.assign.productRequired');
        return errors;
      }
      if (!chosen.metered) {
        // same rule as the core (@Positive), checked here to show it while typing
        if (!isPositiveNumber(values.quota)) {
          errors.quota = t('orgDetail.assign.quotaInvalid');
        }
        return errors;
      }
      // without an access role, a metered product bought without any feature would give nothing
      if (values.boughtFeatures.length === 0) {
        errors.boughtFeatures = t('orgDetail.assign.boughtFeaturesRequired');
      }
      for (const feature of boughtMeteredFeatures(chosen, values.boughtFeatures)) {
        if (!isPositiveNumber(values.featureQuotas[feature]?.quota ?? '')) {
          errors[`featureQuotas.${feature}.quota`] = t('orgDetail.assign.quotaInvalid');
        }
      }
      return errors;
    },
  });

  // the product chosen in the form: its kind, its features and its parameters drive the rest of the form
  const product = findProduct(form.values.productKey);

  const assignedKeys = assigned.map((a) => productKey(a.catalog, a.product));
  // a new assignment only offers the products the org does not have yet
  const options = productOptions(products.filter((p) => current !== null || !assignedKeys.includes(productKey(p.catalog, p.name))));

  function changeProduct(key: string | null) {
    // the features, quotas and parameters of the previous product mean nothing for the new one
    form.setValues({ productKey: key, boughtFeatures: [], featureQuotas: {}, params: {} });
  }

  function changeBoughtFeatures(features: string[]) {
    form.setFieldValue('boughtFeatures', features);
    // a feature newly bought gets an empty quota row, daily by default (only shown for a metered feature)
    const rows = { ...form.values.featureQuotas };
    for (const feature of features) {
      if (!rows[feature]) {
        rows[feature] = { quota: '', cycle: 'DAILY' };
      }
    }
    form.setFieldValue('featureQuotas', rows);
  }

  function close() {
    form.reset();
    assignProduct.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    // the validation above guarantees a product, and numbers where a quota is needed
    const { catalog, name } = splitProductKey(values.productKey!);
    const chosen = findProduct(values.productKey)!;
    // an empty field is left out: the core applies the default of the parameter
    const params = Object.fromEntries(Object.entries(values.params).filter(([, value]) => value !== ''));
    // the kind of the product decides which quotas are sent: the core refuses the others
    const featureQuotas: Record<string, FeatureQuota> = {};
    if (chosen.metered) {
      for (const feature of boughtMeteredFeatures(chosen, values.boughtFeatures)) {
        const row = values.featureQuotas[feature];
        featureQuotas[feature] = { quota: Number(row.quota), cycle: row.cycle };
      }
    }
    assignProduct.mutate(
      {
        catalog,
        product: name,
        request: {
          quota: chosen.metered ? null : Number(values.quota),
          cycle: chosen.metered ? null : values.cycle,
          boughtFeatures: values.boughtFeatures,
          featureQuotas,
          params,
        },
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
          {/* a simple product: one quota for the product (also while no product is chosen yet) */}
          {!product?.metered && (
            <>
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
            </>
          )}

          {product?.metered && (
            <Text size="sm" c="dimmed">
              {t('orgDetail.assign.meteredHint')}
            </Text>
          )}

          {/* only for a product that has features: the ceiling of what the teams of the org can give */}
          {product && product.features.length > 0 && (
            <MultiSelect
              label={t('orgDetail.assign.boughtFeatures')}
              description={t('orgDetail.assign.boughtFeaturesDescription')}
              data={product.features.map((feature) => ({
                value: feature.name,
                label: feature.metered
                  ? t('orgDetail.assign.meteredOption', { feature: feature.displayName ?? feature.name })
                  : (feature.displayName ?? feature.name),
              }))}
              withAsterisk={product.metered}
              value={form.values.boughtFeatures}
              onChange={changeBoughtFeatures}
              error={form.errors.boughtFeatures}
            />
          )}

          {/* a metered product: one quota and its cycle per metered feature bought, added and removed with the selection */}
          {product?.metered &&
            boughtMeteredFeatures(product, form.values.boughtFeatures).map((feature) => (
              <Group key={feature} align="flex-start" grow>
                <NumberInput
                  label={t('orgDetail.assign.featureQuota', { feature })}
                  placeholder="200"
                  min={1}
                  allowDecimal={false}
                  allowNegative={false}
                  thousandSeparator=" "
                  withAsterisk
                  {...form.getInputProps(`featureQuotas.${feature}.quota`)}
                />
                <Select
                  label={t('orgDetail.assign.cycle')}
                  data={CYCLES.map((cycle) => ({ value: cycle, label: t(`orgDetail.cycles.${cycle}`) }))}
                  allowDeselect={false}
                  withAsterisk
                  {...form.getInputProps(`featureQuotas.${feature}.cycle`)}
                />
              </Group>
            ))}

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
