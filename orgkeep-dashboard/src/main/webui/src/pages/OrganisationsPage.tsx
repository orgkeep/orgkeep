import { useState } from 'react';
import { Alert, Anchor, Badge, Button, Center, Group, Loader, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { useOrganisations } from '../api/organisations';
import { productKey, productOptions, splitProductKey, useProducts } from '../api/products';
import CreateOrganisationModal from './CreateOrganisationModal';
import { personName } from './personName';

// The organisations of the realm (realm owner): search, list, create.
export default function OrganisationsPage() {
  const { t } = useTranslation();
  // what the user types, updated on every key stroke
  const [search, setSearch] = useState('');
  // the same value, but only once the user stopped typing for 300 ms: one call to the backend per search,
  // not one per letter
  const [debouncedSearch] = useDebouncedValue(search.trim(), 300);
  // The product filter lives in the URL (/organisations?catalog=prod&product=billing), not in a useState:
  // a link from another page (the catalogs) can open the list already filtered, and the filtered
  // list can be bookmarked. useSearchParams reads and writes the query string, like @QueryParam but both ways.
  const [searchParams, setSearchParams] = useSearchParams();
  const catalog = searchParams.get('catalog') ?? '';
  const product = searchParams.get('product') ?? '';
  const { data: products } = useProducts();
  const { data: orgs, isPending, isFetching, error } = useOrganisations({ search: debouncedSearch, catalog, product });
  const [createOpened, { open: openCreate, close: closeCreate }] = useDisclosure();

  function changeProduct(key: string | null) {
    // an empty filter is removed from the URL rather than kept as "?catalog=&product="
    if (!key) {
      setSearchParams({});
      return;
    }
    const { catalog, name } = splitProductKey(key);
    setSearchParams({ catalog, product: name });
  }

  return (
    <Stack maw={1100}>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={2}>{t('organisations.title')}</Title>
          <Text c="dimmed">{t('organisations.subtitle')}</Text>
        </div>
        <Button onClick={openCreate}>{t('organisations.new')}</Button>
      </Group>

      <Group>
        <TextInput
          placeholder={t('organisations.searchPlaceholder')}
          aria-label={t('organisations.searchLabel')}
          value={search}
          onChange={(event) => setSearch(event.currentTarget.value)}
          // a small spinner while a new search is loading, the table keeps the previous result meanwhile
          rightSection={isFetching && !isPending ? <Loader size="xs" /> : null}
          w={360}
          maw="100%"
        />
        <Select
          placeholder={t('organisations.allProducts')}
          aria-label={t('organisations.productFilterLabel')}
          // {value, label} pairs grouped by catalog: the value "catalog/product" goes to the URL, the display name is shown
          data={productOptions(products ?? [])}
          value={catalog && product ? productKey(catalog, product) : null}
          onChange={changeProduct}
          // the small cross that empties the field: back to all products
          clearable
          w={220}
        />
        {orgs && <Text c="dimmed">{t('organisations.count', { count: orgs.length })}</Text>}
      </Group>

      {isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}

      {error && (
        <Alert color="red" title={t('organisations.loadError')}>
          {error.message}
        </Alert>
      )}

      {orgs && orgs.length === 0 && (
        <Text c="dimmed">{debouncedSearch || catalog || product ? t('organisations.noMatch') : t('organisations.empty')}</Text>
      )}

      {orgs && orgs.length > 0 && (
        // scrolls horizontally on small screens instead of squeezing the columns
        <Table.ScrollContainer minWidth={800}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('organisations.columns.name')}</Table.Th>
                <Table.Th>{t('organisations.columns.emailDomains')}</Table.Th>
                <Table.Th>{t('organisations.columns.owner')}</Table.Th>
                <Table.Th>{t('organisations.columns.members')}</Table.Th>
                <Table.Th>{t('organisations.columns.products')}</Table.Th>
                <Table.Th>{t('organisations.columns.status')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {orgs.map((org) => (
                // key: lets React match the rows between two renders, like an id in a JPA collection
                <Table.Tr key={org.id}>
                  <Table.Td>
                    {/* component={Link}: Mantine draws the link, React Router opens the page without a reload */}
                    <Anchor component={Link} to={`/organisations/${org.name}`} ff="monospace" fw={500}>
                      {org.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td c="dimmed">{org.emailDomains.join(', ')}</Table.Td>
                  <Table.Td>
                    {org.owners.length === 0 ? (
                      <Text c="dimmed" size="sm">
                        {t('organisations.noOwner')}
                      </Text>
                    ) : (
                      org.owners.map((owner) => <div key={owner.id}>{personName(owner)}</div>)
                    )}
                  </Table.Td>
                  <Table.Td>{org.memberCount}</Table.Td>
                  {/* a count, not the list: it opens the products tab of the org, with the quotas.
                      The "catalog/product" pairs are in the tooltip (title) */}
                  <Table.Td title={org.products.join(', ')}>
                    {org.products.length === 0 ? (
                      <Text c="dimmed" size="sm">
                        {t('organisations.noProducts')}
                      </Text>
                    ) : (
                      <Anchor component={Link} to={`/organisations/${org.name}?tab=products`} size="sm">
                        {t('organisations.productCount', { count: org.products.length })}
                      </Anchor>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge color={org.enabled ? 'green' : 'gray'} variant="light">
                      {org.enabled ? t('organisations.status.active') : t('organisations.status.disabled')}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      <CreateOrganisationModal opened={createOpened} onClose={closeCreate} />
    </Stack>
  );
}
