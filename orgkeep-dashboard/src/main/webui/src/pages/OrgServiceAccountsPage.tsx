import { Stack, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import ServiceAccountsSection from './ServiceAccountsSection';

// The service accounts of an org, for its org admins (/orgs/:org/service-accounts). The realm owner has the same
// section in the Service accounts tab of the org. Everything is in the section: this page only gives it the org.
export default function OrgServiceAccountsPage() {
  const { t } = useTranslation();
  const org = useParams().org!;

  return (
    <Stack maw={1100}>
      <Title order={2}>{t('serviceAccounts.title', { org })}</Title>
      <ServiceAccountsSection org={org} />
    </Stack>
  );
}
