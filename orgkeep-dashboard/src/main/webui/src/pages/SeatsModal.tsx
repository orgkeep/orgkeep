import { Alert, Button, Group, Modal, NumberInput, Stack } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type Seats, useUpdateSeats } from '../api/organisations';

interface SeatsModalProps {
  org: string;
  seats: Seats;
  opened: boolean;
  onClose: () => void;
}

interface FormValues {
  // NumberInput gives '' while the field is empty: no limit
  maxMembers: number | '';
  maxServiceAccounts: number | '';
}

// Changes the seat limits of the organisation (realm owner only). Replaces both at once; an empty field removes
// the corresponding limit. The parent gives this component a key tied to the current limits, so it starts from
// them again after a save (useForm only reads its initial values once).
export default function SeatsModal({ org, seats, opened, onClose }: SeatsModalProps) {
  const { t } = useTranslation();
  const update = useUpdateSeats(org);

  const form = useForm<FormValues>({
    initialValues: {
      maxMembers: seats.members.max ?? '',
      maxServiceAccounts: seats.serviceAccounts.max ?? '',
    },
  });

  function close() {
    form.reset();
    update.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    update.mutate(
      {
        maxMembers: values.maxMembers === '' ? null : values.maxMembers,
        maxServiceAccounts: values.maxServiceAccounts === '' ? null : values.maxServiceAccounts,
      },
      { onSuccess: close },
    );
  });

  return (
    <Modal opened={opened} onClose={close} title={t('orgDetail.seats.editTitle', { org })}>
      <form onSubmit={submit}>
        <Stack>
          <NumberInput
            label={t('orgDetail.seats.maxMembers')}
            description={t('orgDetail.seats.noLimitHint')}
            min={0}
            allowDecimal={false}
            allowNegative={false}
            {...form.getInputProps('maxMembers')}
          />
          <NumberInput
            label={t('orgDetail.seats.maxServiceAccounts')}
            description={t('orgDetail.seats.noLimitHint')}
            min={0}
            allowDecimal={false}
            allowNegative={false}
            {...form.getInputProps('maxServiceAccounts')}
          />

          {update.error && (
            <Alert color="red" title={t('common.failed')}>
              {update.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={update.isPending}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
