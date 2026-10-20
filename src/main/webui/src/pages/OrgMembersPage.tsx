import { Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

// Placeholder: the members of an org (org admins), through the core API.
export default function OrgMembersPage() {
  const { t } = useTranslation();
  // the :org part of the route /orgs/:org/members, like a @PathParam
  const { org } = useParams();

  return (
    <>
      <Title order={2}>{t('members.title', { org })}</Title>
      <Text c="dimmed">{t('common.comingSoon')}</Text>
    </>
  );
}
