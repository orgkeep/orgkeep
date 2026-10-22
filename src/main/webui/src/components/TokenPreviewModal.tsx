import { useState } from 'react';
import { Alert, Code, Loader, Modal, MultiSelect, Stack, Tabs, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { type OrgMember, type TokenPreview, useOrgProducts, useTokenPreview } from '../api/organisations';
import { personName } from '../pages/personName';

interface TokenPreviewModalProps {
  org: string;
  // null: closed
  member: OrgMember | null;
  onClose: () => void;
}

// What the APIs of the realm owner receive in the access token of a member (realm owner only): the claims Keycloak
// computes for the login client, without the member logging in. Never a signed token: nothing here can call an API.
// With lightweight tokens, two views: the lightweight token, and what an API reads back by introspection.
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
        {preview.data && !preview.data.lightweight && <Claims claims={preview.data.token} />}
        {preview.data && preview.data.lightweight && <LightweightViews preview={preview.data} catalogs={catalogs} />}

        <Text size="sm" c="dimmed">
          {t('tokenPreview.hint')}
        </Text>
      </Stack>
    </Modal>
  );
}

// Two tabs: the introspection first, it holds the rights; then the lightweight token, what travels
function LightweightViews({ preview, catalogs }: { preview: TokenPreview; catalogs: string[] }) {
  const { t } = useTranslation();
  // "aud" is a list, or a single string with one audience. The API of a catalog may only introspect the tokens
  // whose audience it is in: Keycloak answers "active": false otherwise
  const aud = preview.introspection.aud;
  const audience = Array.isArray(aud) ? aud : typeof aud === 'string' ? [aud] : [];
  const refused = catalogs.filter((catalog) => !audience.includes(catalog));

  return (
    <Stack gap="xs">
      <Text size="sm" c="dimmed">
        {t('tokenPreview.lightweight')}
      </Text>
      {refused.length > 0 && (
        <Alert color="yellow">{t('tokenPreview.notInAudience', { catalogs: refused.join(', ') })}</Alert>
      )}
      <Tabs defaultValue="introspection">
        <Tabs.List>
          <Tabs.Tab value="introspection">{t('tokenPreview.introspectionTab')}</Tabs.Tab>
          <Tabs.Tab value="token">{t('tokenPreview.tokenTab')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="introspection" pt="xs">
          <Claims claims={preview.introspection} />
        </Tabs.Panel>
        <Tabs.Panel value="token" pt="xs">
          <Claims claims={preview.token} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

// null, 2: indented by 2 spaces, like a pretty printer
function Claims({ claims }: { claims: Record<string, unknown> }) {
  return (
    <Code block mah={480} style={{ overflow: 'auto' }}>
      {JSON.stringify(claims, null, 2)}
    </Code>
  );
}
