import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';

interface SuspendModalProps {
  opened: boolean;
  onClose: () => void;
  // the member or service account being suspended, shown in the title
  target: string;
  // the reason already on record, to edit it; null the first time (a new suspension)
  currentReason: string | null;
  pending: boolean;
  error: Error | null;
  onSubmit: (reason: string) => void;
}

// A required reason, up to 500 characters, to suspend a member or a service account, or to edit the reason of an
// existing suspension: realm owner only. Lifting the suspension is a plain confirmation (ConfirmModal), opened
// next to this one by the caller.
export default function SuspendModal({ opened, onClose, target, currentReason, pending, error, onSubmit }: SuspendModalProps) {
  const { t } = useTranslation();
  const editing = currentReason !== null;

  const form = useForm({
    // read once, when the component is created: the parent gives it a new key per target (see ServiceAccountsSection)
    initialValues: { reason: currentReason ?? '' },
    validate: {
      reason: (value) =>
        value.trim() === ''
          ? t('suspension.suspend.reasonRequired')
          : value.length > 500
            ? t('suspension.suspend.reasonTooLong')
            : null,
    },
  });

  function close() {
    form.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => onSubmit(values.reason.trim()));

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={editing ? t('suspension.suspend.editTitle', { target }) : t('suspension.suspend.title', { target })}
    >
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            {t('suspension.suspend.message')}
          </Text>
          <Textarea
            label={t('suspension.suspend.reasonLabel')}
            placeholder={t('suspension.suspend.reasonPlaceholder')}
            withAsterisk
            autosize
            minRows={3}
            maxLength={500}
            data-autofocus
            {...form.getInputProps('reason')}
          />
          {error && (
            <Alert color="red" title={t('common.failed')}>
              {error.message}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" color="orange" loading={pending}>
              {editing ? t('common.save') : t('suspension.suspend.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
