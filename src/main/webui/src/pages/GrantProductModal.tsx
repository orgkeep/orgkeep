import {
  Alert,
  Button,
  Group,
  Input,
  Modal,
  MultiSelect,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Text,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import type { QuotaCycle } from '../api/organisations';
import { productKey, splitProductKey } from '../api/products';
import {
  type AvailableFeature,
  type AvailableProduct,
  type GrantProductRequest,
  type TeamProduct,
  useGrantProduct,
} from '../api/teams';

interface GrantProductModalProps {
  org: string;
  team: string;
  opened: boolean;
  onClose: () => void;
  // the products of the org to choose from (those the team does not give yet, when giving a new one)
  available: AvailableProduct[];
  // set: "Edit" of what the team gives of this product. Null: "Give a product"
  current: TeamProduct | null;
}

// The quota of team for one metered feature, as typed in the form
interface FeatureQuotaValues {
  // NumberInput gives '' while the field is empty: no quota of team for this feature, only the pool of the org
  quota: number | string;
  cycle: QuotaCycle;
}

interface FormValues {
  // "catalog/product", see productKey
  productKey: string | null;
  // true: every feature the org bought, also those it buys later (features: null for the core)
  allFeatures: boolean;
  features: string[];
  // NumberInput gives '' while the field is empty: no quota of team (simple product only)
  quota: number | string;
  cycle: QuotaCycle;
  // metered product: one entry per metered feature the org bought, by feature name. Only those the team gives and
  // that have a quota are sent
  featureQuotas: Record<string, FeatureQuotaValues>;
}

const CYCLES: QuotaCycle[] = ['DAILY', 'MONTHLY', 'YEARLY'];

// empty is fine (no quota of team), otherwise the same rule as the core (@Positive)
function isValidQuota(value: number | string): boolean {
  return value === '' || (typeof value === 'number' && value > 0);
}

// The metered features of a product the org bought: those that can have a quota of team
function meteredFeatures(offer: AvailableProduct | null): AvailableFeature[] {
  return offer ? offer.boughtFeatures.filter((feature) => feature.metered) : [];
}

// The form values of the quotas of team of a metered product: what the team has (when editing), otherwise empty with
// the cycle of the org for the feature, which the quota of team usually follows
function initialFeatureQuotas(offer: AvailableProduct | null, current: TeamProduct | null): Record<string, FeatureQuotaValues> {
  const values: Record<string, FeatureQuotaValues> = {};
  for (const feature of meteredFeatures(offer)) {
    const existing = current?.featureQuotas[feature.name];
    values[feature.name] = { quota: existing?.quota ?? '', cycle: existing?.cycle ?? feature.cycle ?? 'DAILY' };
  }
  return values;
}

// Gives a product of the org to a team, or edits what the team gives of it: the core has a single PUT for both.
// A simple product takes an optional quota of team. A metered product takes at least one feature (the core answers 400
// otherwise) and an optional quota of team per metered feature given, on top of the pool of the org for that feature.
export default function GrantProductModal({ org, team, opened, onClose, available, current }: GrantProductModalProps) {
  const { t, i18n } = useTranslation();
  const grantProduct = useGrantProduct(org, team);

  function offerOf(key: string | null): AvailableProduct | null {
    return available.find((p) => productKey(p.catalog, p.product) === key) ?? null;
  }

  const initialKey = current ? productKey(current.catalog, current.product) : null;
  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each product (see TeamPage)
    initialValues: {
      productKey: initialKey,
      allFeatures: current ? current.features === null : true,
      features: current?.features ?? [],
      quota: current?.quota ?? '',
      // the form proposes the cycle of the org: the quota of the team usually follows it
      cycle: current?.cycle ?? offerOf(initialKey)?.cycle ?? 'MONTHLY',
      featureQuotas: initialFeatureQuotas(offerOf(initialKey), current),
    },
    // a function rather than one rule per field: the quotas to check depend on the features given.
    // The errors are put under the path of the field ('featureQuotas.scan.quota'), where getInputProps finds them
    validate: (values) => {
      const errors: Record<string, string> = {};
      if (!values.productKey) {
        errors.productKey = t('team.grant.productRequired');
      }
      // a metered product without any feature would give nothing: it has no access role
      if (offerOf(values.productKey)?.metered && !values.allFeatures && values.features.length === 0) {
        errors.features = t('team.grant.featuresRequired');
      }
      if (!isValidQuota(values.quota)) {
        errors.quota = t('team.grant.quotaInvalid');
      }
      for (const feature of givenMeteredFeatures(values)) {
        if (!isValidQuota(values.featureQuotas[feature.name]?.quota ?? '')) {
          errors[`featureQuotas.${feature.name}.quota`] = t('team.grant.quotaInvalid');
        }
      }
      return errors;
    },
  });

  const offer = offerOf(form.values.productKey);
  // the language of the page decides the thousand separator: 50,000 in English, 50 000 in French
  const numberFormat = new Intl.NumberFormat(i18n.language);

  // The metered features the team gives: every one bought, or those selected. Only they can have a quota of team
  function givenMeteredFeatures(values: FormValues): AvailableFeature[] {
    return meteredFeatures(offerOf(values.productKey)).filter(
      (feature) => values.allFeatures || values.features.includes(feature.name),
    );
  }

  function changeProduct(key: string | null) {
    // the features and quotas of the previous product mean nothing for the new one; its cycles are the new defaults
    form.setValues({
      productKey: key,
      features: [],
      quota: '',
      cycle: offerOf(key)?.cycle ?? 'MONTHLY',
      featureQuotas: initialFeatureQuotas(offerOf(key), null),
    });
  }

  // a metered feature shows the quota of the org for it: what the members of the team share
  function featureLabel(feature: AvailableFeature): string {
    const label = feature.displayName ?? feature.name;
    if (!feature.metered || feature.quota === null || feature.cycle === null) {
      return label;
    }
    return t('team.grant.meteredOption', {
      feature: label,
      quota: numberFormat.format(feature.quota),
      cycle: t(`orgDetail.cycles.${feature.cycle}`),
    });
  }

  function close() {
    form.reset();
    grantProduct.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    const { catalog, name } = splitProductKey(values.productKey!);
    const metered = offerOf(values.productKey)?.metered ?? false;
    // no quota of team for the product itself on a metered product: the core refuses it (400)
    const quota = values.quota === '' || metered ? null : Number(values.quota);
    // only the metered features given with a quota: a feature not given would be refused by the core (400)
    const featureQuotas: GrantProductRequest['featureQuotas'] = {};
    for (const feature of givenMeteredFeatures(values)) {
      const typed = values.featureQuotas[feature.name];
      if (typed && typed.quota !== '') {
        featureQuotas[feature.name] = { quota: Number(typed.quota), cycle: typed.cycle };
      }
    }
    grantProduct.mutate(
      {
        catalog,
        product: name,
        request: {
          features: values.allFeatures ? null : values.features,
          quota,
          // no cycle without a quota: the core refuses it (400)
          cycle: quota === null ? null : values.cycle,
          featureQuotas,
        },
      },
      { onSuccess: close },
    );
  });

  // grouped by catalog, like the assignment modal of the realm owner
  const options = Array.from(new Set(available.map((p) => p.catalog))).map((catalog) => ({
    group: catalog,
    items: available
      .filter((p) => p.catalog === catalog)
      .map((p) => ({ value: productKey(p.catalog, p.product), label: p.displayName ?? p.product })),
  }));

  const quotaFeatures = givenMeteredFeatures(form.values);

  return (
    <Modal
      opened={opened}
      onClose={close}
      size="lg"
      title={
        current
          ? t('team.grant.editTitle', { product: productKey(current.catalog, current.product), team })
          : t('team.grant.title', { team })
      }
    >
      <form onSubmit={submit}>
        <Stack>
          <Select
            label={t('team.grant.product')}
            placeholder={t('team.grant.productPlaceholder')}
            data={options}
            disabled={current !== null}
            withAsterisk
            searchable
            value={form.values.productKey}
            onChange={changeProduct}
            error={form.errors.productKey}
          />

          {/* only for a product of which the org bought features: otherwise there is nothing to choose */}
          {offer && offer.boughtFeatures.length > 0 && (
            <>
              <Switch
                label={t('team.grant.allFeatures')}
                description={t('team.grant.allFeaturesDescription')}
                {...form.getInputProps('allFeatures', { type: 'checkbox' })}
              />
              {!form.values.allFeatures && (
                <MultiSelect
                  label={t('team.grant.features')}
                  description={
                    offer.metered ? t('team.grant.featuresDescriptionMetered') : t('team.grant.featuresDescription')
                  }
                  data={offer.boughtFeatures.map((feature) => ({ value: feature.name, label: featureLabel(feature) }))}
                  withAsterisk={offer.metered}
                  {...form.getInputProps('features')}
                />
              )}
            </>
          )}

          {/* a metered product: the members share the quotas of the org, plus a quota of team per feature if set */}
          {offer?.metered && (
            <Text size="sm" c="dimmed">
              {t('team.grant.meteredHint', { pool: org })}
            </Text>
          )}

          {offer?.metered && quotaFeatures.length > 0 && (
            <Input.Wrapper
              label={t('team.grant.featureQuotas')}
              description={t('team.grant.featureQuotasDescription', { pool: `${org}/${team}` })}
            >
              {/* one line per metered feature given: the feature and the pool of the org, the quota, its cycle */}
              <Stack gap="xs" mt="xs">
                {quotaFeatures.map((feature) => (
                  <Group key={feature.name} gap="sm" wrap="nowrap" align="flex-start">
                    <Stack gap={0} w={150}>
                      <Text size="sm" fw={500} ff="monospace">
                        {feature.name}
                      </Text>
                      {feature.quota !== null && feature.cycle !== null && (
                        <Text size="xs" c="dimmed">
                          {t('team.grant.orgQuota', {
                            quota: numberFormat.format(feature.quota),
                            cycle: t(`orgDetail.cycles.${feature.cycle}`),
                          })}
                        </Text>
                      )}
                    </Stack>
                    <NumberInput
                      placeholder={t('team.grant.featureQuotaPlaceholder')}
                      min={1}
                      allowDecimal={false}
                      allowNegative={false}
                      thousandSeparator=" "
                      flex={1}
                      {...form.getInputProps(`featureQuotas.${feature.name}.quota`)}
                    />
                    <Select
                      data={CYCLES.map((cycle) => ({ value: cycle, label: t(`orgDetail.cycles.${cycle}`) }))}
                      allowDeselect={false}
                      // a cycle means nothing without a quota
                      disabled={form.values.featureQuotas[feature.name]?.quota === ''}
                      w={140}
                      {...form.getInputProps(`featureQuotas.${feature.name}.cycle`)}
                    />
                  </Group>
                ))}
              </Stack>
            </Input.Wrapper>
          )}

          {!offer?.metered && (
            <NumberInput
              label={t('team.grant.quota')}
              description={t('team.grant.quotaDescription', {
                quota: offer?.quota ?? '-',
                cycle: offer?.cycle ? t(`orgDetail.cycles.${offer.cycle}`) : '-',
              })}
              min={1}
              allowDecimal={false}
              allowNegative={false}
              thousandSeparator=" "
              {...form.getInputProps('quota')}
            />
          )}
          {!offer?.metered && form.values.quota !== '' && (
            // Input.Wrapper adds the label above a component that has none, like SegmentedControl
            <Input.Wrapper label={t('team.grant.cycle')}>
              <SegmentedControl
                fullWidth
                data={CYCLES.map((cycle) => ({ value: cycle, label: t(`orgDetail.cycles.${cycle}`) }))}
                {...form.getInputProps('cycle')}
              />
            </Input.Wrapper>
          )}
          {!offer?.metered && form.values.quota !== '' && (
            <Text size="sm" c="dimmed">
              {t('team.grant.poolHint', { pool: `${org}/${team}` })}
            </Text>
          )}

          {grantProduct.error && (
            <Alert color="red" title={t('common.failed')}>
              {grantProduct.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={grantProduct.isPending}>
              {current ? t('common.save') : t('team.grant.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
