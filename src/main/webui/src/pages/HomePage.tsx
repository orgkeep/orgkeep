import { Badge, Group, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { CurrentUser } from '../api/currentUser';
import { useServerInfo } from '../api/serverInfo';

export default function HomePage({ user }: { user: CurrentUser }) {
  const { t } = useTranslation();
  const { data: serverInfo } = useServerInfo();

  return (
    <Stack>
      {/* {{name}} in the JSON file is replaced by the value given here */}
      <Title order={2}>{t('home.welcome', { name: user.fullName ?? user.username })}</Title>

      <Text fw={500}>{t('home.realmRoles')}</Text>
      <Group gap="xs">
        {user.realmRoles.map((role) => (
          <Badge key={role} variant="light">
            {role}
          </Badge>
        ))}
      </Group>

      <Text fw={500}>{t('home.organisations')}</Text>
      {Object.keys(user.orgRoles).length === 0 && <Text c="dimmed">{t('home.none')}</Text>}
      {Object.entries(user.orgRoles).map(([org, roles]) => (
        <Group key={org} gap="xs">
          <Text>{org}</Text>
          {roles.map((role) => (
            <Badge key={role} variant="outline">
              {role}
            </Badge>
          ))}
        </Group>
      ))}

      {serverInfo && (
        <Text size="xs" c="dimmed">
          {serverInfo.application} {serverInfo.version}
        </Text>
      )}
    </Stack>
  );
}
