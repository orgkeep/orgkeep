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
import { type AvailableFeature, type AvailableProduct, type TeamProduct, useGrantProduct } from '../api/teams';

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

interface FormValues {
  // "catalog/product", see productKey
  productKey: string | null;
  // true: every feature the org bought, also those it buys later (features: null for the core)
  allFeatures: boolean;
  features: string[];
  // NumberInput gives '' while the field is empty: no quota of team (simple product only)
  quota: number | string;
  cycle: QuotaCycle;
}

const CYCLES: QuotaCycle[] = ['DAILY', 'MONTHLY', 'YEARLY'];

// Gives a product of the org to a team, or edits what the team gives of it: the core has a single PUT for both.
// A metered product takes no quota of team (its members use the quotas of the org, one per metered feature), and at
// least one feature: the core answers 400 otherwise.
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
    },
    validate: {
      productKey: (value) => (value ? null : t('team.grant.productRequired')),
      // a metered product without any feature would give nothing: it has no access role
      features: (value, values) =>
        offerOf(values.productKey)?.metered && !values.allFeatures && value.length === 0
          ? t('team.grant.featuresRequired')
          : null,
      // empty is fine (no quota of team), otherwise the same rule as the core (@Positive)
      quota: (value) => (value === '' || (typeof value === 'number' && value > 0) ? null : t('team.grant.quotaInvalid')),
    },
  });

  const offer = offerOf(form.values.productKey);
  // the language of the page decides the thousand separator: 50,000 in English, 50 000 in French
  const numberFormat = new Intl.NumberFormat(i18n.language);

  function changeProduct(key: string | null) {
    // the features of the previous product mean nothing for the new one; its cycle is the new default
    form.setValues({ productKey: key, features: [], quota: '', cycle: offerOf(key)?.cycle ?? 'MONTHLY' });
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
    // no quota of team on a metered product: the core refuses it (400)
    const quota = values.quota === '' || offerOf(values.productKey)?.metered ? null : Number(values.quota);
    grantProduct.mutate(
      {
        catalog,
        product: name,
        request: {
          features: values.allFeatures ? null : values.features,
          quota,
          // no cycle without a quota: the core refuses it (400)
          cycle: quota === null ? null : values.cycle,
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

  return (
    <Modal
      opened={opened}
      onClose={close}
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

          {/* a metered product: the members share the quotas of the org, one per metered feature */}
          {offer?.metered && (
            <Text size="sm" c="dimmed">
              {t('team.grant.meteredHint', { pool: org })}
            </Text>
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
