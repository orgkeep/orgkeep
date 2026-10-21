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
import { type AvailableProduct, type TeamProduct, useGrantProduct } from '../api/teams';

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
  // true: every role the org bought, also those it buys later (roles: null for the core)
  allRoles: boolean;
  roles: string[];
  // NumberInput gives '' while the field is empty: no quota of team
  quota: number | string;
  cycle: QuotaCycle;
}

const CYCLES: QuotaCycle[] = ['DAILY', 'MONTHLY', 'YEARLY'];

// Gives a product of the org to a team, or edits what the team gives of it: the core has a single PUT for both.
export default function GrantProductModal({ org, team, opened, onClose, available, current }: GrantProductModalProps) {
  const { t } = useTranslation();
  const grantProduct = useGrantProduct(org, team);

  function offerOf(key: string | null): AvailableProduct | null {
    return available.find((p) => productKey(p.catalog, p.product) === key) ?? null;
  }

  const initialKey = current ? productKey(current.catalog, current.product) : null;
  const form = useForm<FormValues>({
    // read once, when the component is created: the parent gives it a new key for each product (see TeamPage)
    initialValues: {
      productKey: initialKey,
      allRoles: current ? current.roles === null : true,
      roles: current?.roles ?? [],
      quota: current?.quota ?? '',
      // the form proposes the cycle of the org: the quota of the team usually follows it
      cycle: current?.cycle ?? offerOf(initialKey)?.cycle ?? 'MONTHLY',
    },
    validate: {
      productKey: (value) => (value ? null : t('team.grant.productRequired')),
      // empty is fine (no quota of team), otherwise the same rule as the core (@Positive)
      quota: (value) => (value === '' || (typeof value === 'number' && value > 0) ? null : t('team.grant.quotaInvalid')),
    },
  });

  const offer = offerOf(form.values.productKey);

  function changeProduct(key: string | null) {
    // the roles of the previous product mean nothing for the new one; its cycle is the new default
    form.setValues({ productKey: key, roles: [], cycle: offerOf(key)?.cycle ?? 'MONTHLY' });
  }

  function close() {
    form.reset();
    grantProduct.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    const { catalog, name } = splitProductKey(values.productKey!);
    const quota = values.quota === '' ? null : Number(values.quota);
    grantProduct.mutate(
      {
        catalog,
        product: name,
        request: {
          roles: values.allRoles ? null : values.roles,
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

          {/* only for a product of which the org bought roles: otherwise there is nothing to choose */}
          {offer && offer.boughtRoles.length > 0 && (
            <>
              <Switch
                label={t('team.grant.allRoles')}
                description={t('team.grant.allRolesDescription')}
                {...form.getInputProps('allRoles', { type: 'checkbox' })}
              />
              {!form.values.allRoles && (
                <MultiSelect
                  label={t('team.grant.roles')}
                  description={t('team.grant.rolesDescription')}
                  data={offer.boughtRoles.map((role) => ({ value: role.name, label: role.displayName ?? role.name }))}
                  {...form.getInputProps('roles')}
                />
              )}
            </>
          )}

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
          {form.values.quota !== '' && (
            // Input.Wrapper adds the label above a component that has none, like SegmentedControl
            <Input.Wrapper label={t('team.grant.cycle')}>
              <SegmentedControl
                fullWidth
                data={CYCLES.map((cycle) => ({ value: cycle, label: t(`orgDetail.cycles.${cycle}`) }))}
                {...form.getInputProps('cycle')}
              />
            </Input.Wrapper>
          )}
          {form.values.quota !== '' && (
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
