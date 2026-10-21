import { useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
  Button,
  Center,
  Group,
  Loader,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { type OrgMember, useOrgMembers } from '../api/organisations';
import { productKey } from '../api/products';
import {
  type AvailableProduct,
  type GrantableRole,
  type TeamCatalogRole,
  type TeamProduct,
  type UserGroup,
  useAddTeamMember,
  useAvailableProducts,
  useGrantableRoles,
  useGrantCatalogRole,
  useRemoveTeamMember,
  useRevokeCatalogRole,
  useRevokeProduct,
  useUserGroups,
} from '../api/teams';
import ConfirmModal from '../components/ConfirmModal';
import GrantProductModal from './GrantProductModal';
import { personName } from './personName';

// The detail of a team: its members, the products it gives and its catalog roles.
// Three sections on one page: a team has few of each, and the org admin configures them together.
export default function TeamPage() {
  const { t } = useTranslation();
  // the :org and :team parts of the route /orgs/:org/teams/:team
  const org = useParams().org!;
  const teamName = useParams().team!;
  // the core lists the groups of the org, not one group: the team is picked from the list
  const { data: teams, isPending, error } = useUserGroups(org);

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('teams.loadError', { org })} maw={1100}>
        {error.message}
      </Alert>
    );
  }
  const team = teams.find((group) => group.name === teamName);
  if (!team) {
    // e.g. a name typed in the URL, or a team deleted in another tab
    return (
      <Alert color="red" title={t('team.notFound', { team: teamName })} maw={1100}>
        <Anchor component={Link} to={`/orgs/${org}/teams`}>
          {t('team.backToTeams')}
        </Anchor>
      </Alert>
    );
  }

  return (
    <Stack maw={1100} gap="xl">
      <Stack gap="xs">
        <Breadcrumbs>
          <Anchor component={Link} to={`/orgs/${org}/teams`}>
            {t('teams.title', { org })}
          </Anchor>
          <Text ff="monospace">{team.name}</Text>
        </Breadcrumbs>
        <Group gap="sm">
          <Title order={2} ff="monospace">
            {team.name}
          </Title>
          {team.predefined && (
            <Badge variant="light" color="gray">
              {t('teams.predefined')}
            </Badge>
          )}
        </Group>
        <Text c="dimmed">{t(team.name === 'users' ? 'team.usersHint' : 'team.hint')}</Text>
      </Stack>

      <MembersSection org={org} team={team} />
      <ProductsSection org={org} team={team} />
      <CatalogRolesSection org={org} team={team} />
    </Stack>
  );
}

interface SectionProps {
  org: string;
  team: UserGroup;
}

// The members of the team, among those of the org
function MembersSection({ org, team }: SectionProps) {
  const { t } = useTranslation();
  const { data: orgMembers } = useOrgMembers(org);
  const addMember = useAddTeamMember(org, team.name);
  const removeMember = useRemoveTeamMember(org, team.name);
  const [removeTarget, setRemoveTarget] = useState<OrgMember | null>(null);

  const members = (orgMembers ?? []).filter((member) => team.memberIds.includes(member.id));
  const others = (orgMembers ?? []).filter((member) => !team.memberIds.includes(member.id));

  function add(userId: string | null) {
    if (userId) {
      addMember.mutate(userId);
    }
  }

  function closeRemoveConfirm() {
    removeMember.reset();
    setRemoveTarget(null);
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('team.members.title', { count: members.length })}</Title>
        {/* every member stays in users: nobody to add there */}
        {others.length > 0 && (
          <Select
            placeholder={t('team.members.add')}
            data={others.map((member) => ({ value: member.id, label: personName(member) }))}
            // a Select used as an action: it never keeps a value
            value={null}
            onChange={add}
            searchable
            w={280}
          />
        )}
      </Group>
      {addMember.error && (
        <Alert color="red" title={t('common.failed')} withCloseButton onClose={() => addMember.reset()}>
          {addMember.error.message}
        </Alert>
      )}

      {members.length === 0 ? (
        <Text c="dimmed">{t('team.members.empty')}</Text>
      ) : (
        <Table withTableBorder>
          <Table.Tbody>
            {members.map((member) => (
              <Table.Tr key={member.id}>
                <Table.Td fw={500}>{personName(member)}</Table.Td>
                <Table.Td c="dimmed">{member.email}</Table.Td>
                <Table.Td>
                  <Group justify="flex-end">
                    {/* the core refuses it for users (409): every member of the org stays in it */}
                    {team.name !== 'users' && (
                      <Button variant="subtle" size="xs" color="red" onClick={() => setRemoveTarget(member)}>
                        {t('common.remove')}
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <ConfirmModal
        opened={removeTarget !== null}
        title={t('team.members.removeTitle', { member: removeTarget ? personName(removeTarget) : '', team: team.name })}
        message={t('team.members.removeMessage')}
        confirmLabel={t('common.remove')}
        pending={removeMember.isPending}
        error={removeMember.error}
        onConfirm={() => removeTarget && removeMember.mutate(removeTarget.id, { onSuccess: closeRemoveConfirm })}
        onClose={closeRemoveConfirm}
      />
    </Stack>
  );
}

// The products the team gives, with their roles and quota of team
function ProductsSection({ org, team }: SectionProps) {
  const { t, i18n } = useTranslation();
  const { data: available } = useAvailableProducts(org);
  const revokeProduct = useRevokeProduct(org, team.name);
  // null = closed, 'new' = "Give a product", a product = "Edit" of what the team gives of it
  const [grantTarget, setGrantTarget] = useState<TeamProduct | 'new' | null>(null);
  const [removeTarget, setRemoveTarget] = useState<TeamProduct | null>(null);
  const numberFormat = new Intl.NumberFormat(i18n.language);

  function offer(given: TeamProduct): AvailableProduct | undefined {
    return available?.find((p) => p.catalog === given.catalog && p.product === given.product);
  }

  function displayName(given: TeamProduct): string {
    return offer(given)?.displayName ?? given.product;
  }

  function closeRemoveConfirm() {
    revokeProduct.reset();
    setRemoveTarget(null);
  }

  const givenKeys = team.products.map((p) => productKey(p.catalog, p.product));
  const left = (available ?? []).filter((p) => !givenKeys.includes(productKey(p.catalog, p.product)));

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('team.products.title', { count: team.products.length })}</Title>
        <Button onClick={() => setGrantTarget('new')} disabled={left.length === 0}>
          {t('team.products.give')}
        </Button>
      </Group>
      {available && available.length === 0 && <Text c="dimmed">{t('team.products.noneAssigned')}</Text>}

      {team.products.length === 0 ? (
        <Text c="dimmed">{t('team.products.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={720}>
          <Table withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('team.products.columns.product')}</Table.Th>
                <Table.Th>{t('team.products.columns.roles')}</Table.Th>
                <Table.Th ta="right">{t('team.products.columns.quota')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {team.products.map((given) => (
                <Table.Tr key={productKey(given.catalog, given.product)}>
                  <Table.Td>
                    <Text fw={500}>{displayName(given)}</Text>
                    <Text size="xs" c="dimmed" ff="monospace">
                      {productKey(given.catalog, given.product)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {given.roles === null && (
                      <Badge variant="outline" mr={4}>
                        {t('team.products.allRoles')}
                      </Badge>
                    )}
                    {given.effectiveRoles.length === 0 && given.roles !== null
                      ? '-'
                      : given.effectiveRoles.map((role) => (
                          <Badge key={role} variant="light" mr={4} tt="none">
                            {role}
                          </Badge>
                        ))}
                  </Table.Td>
                  <Table.Td ta="right">
                    {given.quota === null || given.cycle === null
                      ? t('team.products.orgPoolOnly')
                      : t('team.products.quota', {
                          quota: numberFormat.format(given.quota),
                          cycle: t(`orgDetail.cycles.${given.cycle}`),
                        })}
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end" wrap="nowrap">
                      <Button variant="subtle" size="xs" onClick={() => setGrantTarget(given)}>
                        {t('common.edit')}
                      </Button>
                      <Button variant="subtle" size="xs" color="red" onClick={() => setRemoveTarget(given)}>
                        {t('common.remove')}
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {/* key: a new key creates a new component, so the form starts from the values of the chosen product */}
      <GrantProductModal
        key={grantTarget === 'new' || grantTarget === null ? 'new' : productKey(grantTarget.catalog, grantTarget.product)}
        org={org}
        team={team.name}
        opened={grantTarget !== null}
        onClose={() => setGrantTarget(null)}
        available={grantTarget === 'new' ? left : available ?? []}
        current={grantTarget === 'new' ? null : grantTarget}
      />

      <ConfirmModal
        opened={removeTarget !== null}
        title={t('team.products.removeTitle', { product: removeTarget ? displayName(removeTarget) : '' })}
        message={t('team.products.removeMessage')}
        confirmLabel={t('common.remove')}
        pending={revokeProduct.isPending}
        error={revokeProduct.error}
        onConfirm={() =>
          removeTarget &&
          revokeProduct.mutate(
            { catalog: removeTarget.catalog, product: removeTarget.product },
            { onSuccess: closeRemoveConfirm },
          )
        }
        onClose={closeRemoveConfirm}
      />
    </Stack>
  );
}

// The catalog roles the team gives: those the realm owner made grantable, in the catalogs of the org
function CatalogRolesSection({ org, team }: SectionProps) {
  const { t } = useTranslation();
  const { data: grantable } = useGrantableRoles(org);
  const grantRole = useGrantCatalogRole(org, team.name);
  const revokeRole = useRevokeCatalogRole(org, team.name);

  // "catalog/role" as the value of the Select: a role name may exist in several catalogs
  function roleKey(role: { catalog: string; name: string }): string {
    return `${role.catalog}/${role.name}`;
  }

  function label(given: TeamCatalogRole): string {
    const role = grantable?.find((r: GrantableRole) => r.catalog === given.catalog && r.name === given.role);
    return role?.displayName ?? given.role;
  }

  const givenKeys = team.catalogRoles.map((r) => roleKey({ catalog: r.catalog, name: r.role }));
  const left = (grantable ?? []).filter((role) => !givenKeys.includes(roleKey(role)));

  function grant(key: string | null) {
    if (key) {
      const [catalog, role] = key.split('/');
      grantRole.mutate({ catalog, role });
    }
  }

  const error = grantRole.error ?? revokeRole.error;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('team.catalogRoles.title', { count: team.catalogRoles.length })}</Title>
        {left.length > 0 && (
          <Select
            placeholder={t('team.catalogRoles.add')}
            // grouped by catalog, like the products: [{ group: 'prod', items: [...] }]
            data={Array.from(new Set(left.map((role) => role.catalog))).map((catalog) => ({
              group: catalog,
              items: left
                .filter((role) => role.catalog === catalog)
                .map((role) => ({ value: roleKey(role), label: role.displayName ?? role.name })),
            }))}
            value={null}
            onChange={grant}
            w={280}
          />
        )}
      </Group>
      <Text c="dimmed" size="sm">
        {t('team.catalogRoles.hint')}
      </Text>
      {error && (
        <Alert
          color="red"
          title={t('common.failed')}
          withCloseButton
          onClose={() => {
            grantRole.reset();
            revokeRole.reset();
          }}
        >
          {error.message}
        </Alert>
      )}

      {team.catalogRoles.length === 0 ? (
        <Text c="dimmed">{t('team.catalogRoles.empty')}</Text>
      ) : (
        <Table withTableBorder>
          <Table.Tbody>
            {team.catalogRoles.map((given) => (
              <Table.Tr key={`${given.catalog}/${given.role}`}>
                <Table.Td fw={500}>{label(given)}</Table.Td>
                <Table.Td c="dimmed" ff="monospace" fz="sm">
                  resource_access.{given.catalog}: {given.role}
                </Table.Td>
                <Table.Td>
                  <Group justify="flex-end">
                    <Button
                      variant="subtle"
                      size="xs"
                      color="red"
                      loading={revokeRole.isPending && revokeRole.variables?.role === given.role}
                      onClick={() => revokeRole.mutate({ catalog: given.catalog, role: given.role })}
                    >
                      {t('common.remove')}
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
