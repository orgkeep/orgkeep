import { Alert, Button, Code, Group, Modal, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { ServiceAccountCredentials } from '../api/serviceAccounts';
import CopyValue from './CopyValue';

interface ServiceAccountSecretModalProps {
  // null: the modal is closed. The credentials only live in the state of the parent, as long as this modal is open:
  // the core gives the secret in the answer of the creation and of a rotation, and never again
  credentials: ServiceAccountCredentials | null;
  // true after a rotation: the old secret no longer works, the text says so
  rotated: boolean;
  onClose: () => void;
}

// Shows the client ID, the secret and the token URL of a service account, each with a copy button,
// and warns that the secret will not be shown again.
export default function ServiceAccountSecretModal({ credentials, rotated, onClose }: ServiceAccountSecretModalProps) {
  const { t } = useTranslation();

  const rows = credentials
    ? [
        { label: t('serviceAccounts.secret.clientId'), value: credentials.clientId },
        { label: t('serviceAccounts.secret.clientSecret'), value: credentials.clientSecret },
        { label: t('serviceAccounts.secret.tokenUrl'), value: credentials.tokenUrl },
      ]
    : [];

  return (
    <Modal
      opened={credentials !== null}
      onClose={onClose}
      title={t(rotated ? 'serviceAccounts.secret.rotatedTitle' : 'serviceAccounts.secret.createdTitle')}
      size="lg"
      // a click beside the modal or Escape must not lose the secret by accident: only the button closes it
      closeOnClickOutside={false}
      closeOnEscape={false}
    >
      <Stack>
        <Alert color="yellow">{t('serviceAccounts.secret.shownOnce')}</Alert>
        {rotated && <Text size="sm">{t('serviceAccounts.secret.oldStopped')}</Text>}
        <Table>
          <Table.Tbody>
            {rows.map((row) => (
              <Table.Tr key={row.label}>
                <Table.Td w={140}>
                  <Text size="sm" c="dimmed">
                    {row.label}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    {/* flex 1 + minWidth 0: the value takes the room left by the button and wraps inside it */}
                    <Code style={{ wordBreak: 'break-all', flex: 1, minWidth: 0 }}>{row.value}</Code>
                    <CopyValue value={row.value} />
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        <Text size="sm" c="dimmed">
          {t('serviceAccounts.secret.usage')}
        </Text>
        <Group justify="flex-end">
          <Button onClick={onClose}>{t('serviceAccounts.secret.done')}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
