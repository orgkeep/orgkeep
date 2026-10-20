import { Alert, Anchor, Button, Center, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useCatalogs } from '../api/catalogs';
import CatalogModal from './CatalogModal';

// The catalogs of the realm (realm owner): a catalog groups products, and comes out as one claim in the tokens.
export default function CatalogsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: catalogs, isPending, error } = useCatalogs();
  const [createOpened, { open: openCreate, close: closeCreate }] = useDisclosure();

  return (
    <Stack maw={1100}>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>{t('catalogs.title')}</Title>
          <Text c="dimmed">{t('catalogs.subtitle')}</Text>
        </div>
        <Button onClick={openCreate}>{t('catalogs.new')}</Button>
      </Group>

      {isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}

      {error && (
        <Alert color="red" title={t('catalogs.loadError')}>
          {error.message}
        </Alert>
      )}

      {catalogs && catalogs.length === 0 && <Text c="dimmed">{t('catalogs.empty')}</Text>}

      {catalogs && catalogs.length > 0 && (
        <Table.ScrollContainer minWidth={640}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('catalogs.columns.name')}</Table.Th>
                <Table.Th>{t('catalogs.columns.displayName')}</Table.Th>
                <Table.Th>{t('catalogs.columns.description')}</Table.Th>
                <Table.Th>{t('catalogs.columns.products')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {catalogs.map((catalog) => (
                <Table.Tr key={catalog.name}>
                  <Table.Td>
                    <Anchor component={Link} to={`/catalogs/${catalog.name}`} ff="monospace" fw={500}>
                      {catalog.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>{catalog.displayName}</Table.Td>
                  <Table.Td c="dimmed">{catalog.description}</Table.Td>
                  <Table.Td>{t('catalogs.productCount', { count: catalog.productCount })}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {/* once created, the catalog opens on its page: the next step is to add its products */}
      <CatalogModal
        opened={createOpened}
        onClose={closeCreate}
        current={null}
        onSaved={(catalog) => navigate(`/catalogs/${catalog.name}`)}
      />
    </Stack>
  );
}
