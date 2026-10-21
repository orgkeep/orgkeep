import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { useAddOrgMember } from '../api/organisations';

interface AddMemberModalProps {
  org: string;
  opened: boolean;
  onClose: () => void;
}

interface FormValues {
  email: string;
  firstName: string;
  lastName: string;
}

// '' -> null: the names are optional in the core
function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

// Adds a member to the org by email. The core reuses the user if it exists, otherwise creates it and sends the
// invitation email of Keycloak (choose a password).
export default function AddMemberModal({ org, opened, onClose }: AddMemberModalProps) {
  const { t } = useTranslation();
  const addMember = useAddOrgMember(org);

  const form = useForm<FormValues>({
    initialValues: { email: '', firstName: '', lastName: '' },
    validate: {
      // a light check, the core validates the email again (@Email)
      email: (value) => (/^\S+@\S+$/.test(value.trim()) ? null : t('members.form.emailInvalid')),
    },
  });

  function close() {
    form.reset();
    addMember.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    addMember.mutate(
      { email: values.email.trim(), firstName: emptyToNull(values.firstName), lastName: emptyToNull(values.lastName) },
      { onSuccess: close },
    );
  });

  return (
    <Modal opened={opened} onClose={close} title={t('members.form.title', { org })}>
      <form onSubmit={submit}>
        <Stack>
          <TextInput label={t('members.form.email')} withAsterisk {...form.getInputProps('email')} />
          <Group grow>
            <TextInput label={t('members.form.firstName')} {...form.getInputProps('firstName')} />
            <TextInput label={t('members.form.lastName')} {...form.getInputProps('lastName')} />
          </Group>
          <Text size="sm" c="dimmed">
            {t('members.form.invitation')}
          </Text>

          {addMember.error && (
            <Alert color="red" title={t('common.failed')}>
              {addMember.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={addMember.isPending}>
              {t('members.form.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
