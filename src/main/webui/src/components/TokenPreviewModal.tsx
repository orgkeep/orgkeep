import { useState } from 'react';
import { Alert, Code, Loader, Modal, MultiSelect, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { type OrgMember, useOrgProducts, useTokenPreview } from '../api/organisations';
import { personName } from '../pages/personName';

interface TokenPreviewModalProps {
  org: string;
  // null: closed
  member: OrgMember | null;
  onClose: () => void;
}

// What the APIs of the realm owner receive in the access token of a member (realm owner only): the claims Keycloak
// computes for the login client, without the member logging in. Never a signed token: nothing here can call an API.
export default function TokenPreviewModal({ org, member, onClose }: TokenPreviewModalProps) {
  const { t } = useTranslation();
  // the catalogs where the org has a product: the only ones that can put something in its tokens
  const { data: products } = useOrgProducts(org);
  const orgCatalogs = Array.from(new Set((products ?? []).map((product) => product.catalog)));
  // null until the realm owner changes the selection: every catalog of the org by default
  const [selected, setSelected] = useState<string[] | null>(null);
  const catalogs = selected ?? orgCatalogs;

  // the query runs again on each change of the selection, like a search field
  const preview = useTokenPreview(org, member?.id ?? null, catalogs);

  return (
    <Modal
      opened={member !== null}
      onClose={onClose}
      size="xl"
      title={t('tokenPreview.title', { member: member ? personName(member) : '' })}
    >
      <Stack>
        <MultiSelect
          label={t('tokenPreview.catalogs')}
          description={t('tokenPreview.catalogsDescription')}
          data={orgCatalogs}
          value={catalogs}
          onChange={setSelected}
        />
        {/* the scope parameter an application sends to get these catalogs */}
        <Text size="sm" c="dimmed">
          {t('tokenPreview.scope')} <Code>scope={['openid', ...catalogs].join(' ')}</Code>
        </Text>

        {preview.isPending && <Loader size="sm" />}
        {preview.error && (
          <Alert color="red" title={t('common.failed')}>
            {preview.error.message}
          </Alert>
        )}
        {/* null, 2: indented by 2 spaces, like a pretty printer */}
        {preview.data && (
          <Code block mah={480} style={{ overflow: 'auto' }}>
            {JSON.stringify(preview.data, null, 2)}
          </Code>
        )}

        <Text size="sm" c="dimmed">
          {t('tokenPreview.hint')}
        </Text>
      </Stack>
    </Modal>
  );
}
