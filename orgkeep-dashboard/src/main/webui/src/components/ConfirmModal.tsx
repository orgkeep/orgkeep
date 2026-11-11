import type { ReactNode } from 'react';
import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

interface ConfirmModalProps {
  opened: boolean;
  title: string;
  // what will happen, shown in the body of the modal
  message: ReactNode;
  confirmLabel: string;
  // red by default: most confirmations are for a destructive action
  confirmColor?: string;
  // the call runs while the modal stays open: a spinner on the button, and its error below
  pending: boolean;
  error: Error | null;
  onConfirm: () => void;
  onClose: () => void;
}

// "Are you sure?" before a destructive action (red button). The parent runs the call in onConfirm and closes
// the modal once it succeeded: on an error the modal stays open and shows the message of the backend.
export default function ConfirmModal({
  opened,
  title,
  message,
  confirmLabel,
  confirmColor = 'red',
  pending,
  error,
  onConfirm,
  onClose,
}: ConfirmModalProps) {
  const { t } = useTranslation();

  return (
    <Modal opened={opened} onClose={onClose} title={title}>
      <Stack>
        <Text>{message}</Text>
        {error && (
          <Alert color="red" title={t('common.failed')}>
            {error.message}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button color={confirmColor} loading={pending} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
