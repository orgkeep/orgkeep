import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';
import { type UserGroup, useCreateUserGroup } from '../api/teams';
import { isSlug } from './slug';

interface CreateTeamModalProps {
  org: string;
  opened: boolean;
  onClose: () => void;
  // called with the created team, e.g. to open its page
  onCreated: (team: UserGroup) => void;
}

// Creates an empty team: it gives nothing until products or roles are added on its page.
export default function CreateTeamModal({ org, opened, onClose, onCreated }: CreateTeamModalProps) {
  const { t } = useTranslation();
  const createTeam = useCreateUserGroup(org);

  const form = useForm({
    initialValues: { name: '' },
    validate: {
      name: (value) => (isSlug(value) ? null : t('teams.form.nameInvalid')),
    },
  });

  function close() {
    form.reset();
    createTeam.reset();
    onClose();
  }

  const submit = form.onSubmit((values) => {
    createTeam.mutate(values.name.trim(), {
      onSuccess: (team) => {
        close();
        onCreated(team);
      },
    });
  });

  return (
    <Modal opened={opened} onClose={close} title={t('teams.form.title', { org })}>
      <form onSubmit={submit}>
        <Stack>
          <TextInput
            label={t('teams.form.name')}
            description={t('teams.form.nameDescription')}
            placeholder="ops"
            withAsterisk
            {...form.getInputProps('name')}
          />
          <Text size="sm" c="dimmed">
            {t('teams.form.poolHint', { pool: `${org}/${form.values.name.trim() || 'ops'}` })}
          </Text>

          {createTeam.error && (
            <Alert color="red" title={t('common.failed')}>
              {createTeam.error.message}
            </Alert>
          )}

          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={createTeam.isPending}>
              {t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
