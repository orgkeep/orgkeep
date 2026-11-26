import { useEffect, useState } from 'react';
import { Alert, Badge, Button, Center, Group, Loader, Pagination, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { useCurrentUser } from '../api/currentUser';
import { type OrgMember, PAGE_SIZE, useOrgMembers, useRemoveOrgMember, useSetMemberEnabled } from '../api/organisations';
import ConfirmModal from '../components/ConfirmModal';
import SuspensionBadge from '../components/SuspensionBadge';
import AddMemberModal from './AddMemberModal';
import { ROLE_COLORS } from './orgRoles';
import { personName } from './personName';
import MemberName from "../components/MemberName";
import ResendInvitationButton from '../components/ResendInvitationButton';

// The members of an org, managed by its org admins: add (the core sends the invitation), disable / enable and remove.
export default function OrgMembersPage() {
  const { t } = useTranslation();
  // the :org part of the route /orgs/:org/members, like a @PathParam. Always set on this route, hence the "!"
  const org = useParams().org!;
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search.trim(), 300);
  const [page, setPage] = useState(1);
  const { data: memberPage, isPending, error } = useOrgMembers(org, { search: debouncedSearch, page });
  const removeMember = useRemoveOrgMember(org);
  const [addOpened, { open: openAdd, close: closeAdd }] = useDisclosure();
  const [removeTarget, setRemoveTarget] = useState<OrgMember | null>(null);
  const setMemberEnabled = useSetMemberEnabled(org);
  // the member whose disable / enable the admin is confirming
  const [statusTarget, setStatusTarget] = useState<OrgMember | null>(null);
  // /api/me gives no id, so the current user is recognised by the username, which the members list has too
  const { data: currentUser } = useCurrentUser();
  const totalPages = memberPage ? Math.ceil(memberPage.total / PAGE_SIZE) : 0;

  // A page can become empty when the list shrinks (e.g. the last member of a 2nd page of 21 removed, leaving
  // total=20, totalPages=1): bring the page back within range instead of a dead end showing "No member yet."
  // with the Pagination control hidden (it only shows when there is more than one page)
  useEffect(() => {
    if (totalPages > 0 && page > totalPages) {
      setPage(totalPages);
    }
  }, [totalPages, page]);

  function changeSearch(value: string) {
    setPage(1);
    setSearch(value);
  }

  function closeRemoveConfirm() {
    removeMember.reset();
    setRemoveTarget(null);
  }

  function confirmRemove() {
    if (removeTarget) {
      removeMember.mutate(removeTarget.id, { onSuccess: closeRemoveConfirm });
    }
  }

  function closeStatusConfirm() {
    setMemberEnabled.reset();
    setStatusTarget(null);
  }

  function confirmStatus() {
    if (statusTarget) {
      // a member is enabled unless the core says otherwise: the action is the opposite of the current status.
      // A suspended member can only be disabled (the core answers 409 to enabling one): never "enable" here
      const enable = statusTarget.suspension === null && statusTarget.enabled === false;
      setMemberEnabled.mutate({ userId: statusTarget.id, enabled: enable }, { onSuccess: closeStatusConfirm });
    }
  }

  // Only presentation: the core refuses an owner and oneself with a 409 anyway
  function canChangeStatus(member: OrgMember): boolean {
    return member.role !== 'ORG_OWNER' && member.username !== currentUser?.username;
  }

  // the action offered on the member being confirmed: disable an enabled or suspended member, enable a disabled one
  const statusAction = statusTarget && statusTarget.suspension === null && statusTarget.enabled === false ? 'enable' : 'disable';

  if (isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Alert color="red" title={t('members.loadError', { org })} maw={1100}>
        {error.message}
      </Alert>
    );
  }

  const members = memberPage.items;

  return (
    <Stack maw={1100}>
      <Group justify="space-between">
        <Title order={2}>{t('members.title', { org })}</Title>
        <Button onClick={openAdd}>{t('members.add')}</Button>
      </Group>

      <TextInput
        placeholder={t('members.searchPlaceholder')}
        aria-label={t('members.searchLabel')}
        value={search}
        onChange={(event) => changeSearch(event.currentTarget.value)}
        w={320}
        maw="100%"
      />

      {members.length === 0 ? (
        <Text c="dimmed">{debouncedSearch ? t('members.noMatch') : t('members.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={640}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('members.columns.name')}</Table.Th>
                <Table.Th>{t('members.columns.email')}</Table.Th>
                <Table.Th>{t('members.columns.role')}</Table.Th>
                <Table.Th>{t('members.columns.status')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {members.map((member) => (
                <Table.Tr key={member.id}>
                  <Table.Td fw={500}>
                    <MemberName member={member}/>
                  </Table.Td>
                  <Table.Td c="dimmed">{member.email}</Table.Td>
                  <Table.Td>
                    <Badge color={ROLE_COLORS[member.role]} variant="light">
                      {t(`orgDetail.roles.${member.role}`)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    {member.suspension ? (
                      <SuspensionBadge suspension={member.suspension} />
                    ) : (
                      <Badge color={member.enabled === false ? 'gray' : 'green'} variant="light">
                        {t(member.enabled === false ? 'members.status.disabled' : 'members.status.active')}
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Group justify="flex-end" align="flex-start" wrap="nowrap">
                      <ResendInvitationButton org={org} member={member} />
                      {canChangeStatus(member) && (
                        <Button
                          variant="subtle"
                          size="xs"
                          // a suspended member only offers "Disable" (never "Enable": the core answers 409 to it)
                          color={member.suspension === null && member.enabled === false ? 'green' : 'orange'}
                          onClick={() => setStatusTarget(member)}
                        >
                          {t(
                            member.suspension === null && member.enabled === false
                              ? 'members.enable.button'
                              : 'members.disable.button',
                          )}
                        </Button>
                      )}
                      {/* the core refuses to remove an owner (409): not offered */}
                      {member.role !== 'ORG_OWNER' && (
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
        </Table.ScrollContainer>
      )}

      {totalPages > 1 && (
        <Group justify="center">
          <Pagination value={page} onChange={setPage} total={totalPages} />
        </Group>
      )}

      <AddMemberModal org={org} opened={addOpened} onClose={closeAdd} />

      <ConfirmModal
        opened={statusTarget !== null}
        title={t(`members.${statusAction}.title`, { member: statusTarget ? personName(statusTarget) : '' })}
        message={t(`members.${statusAction}.message`)}
        confirmLabel={t(`members.${statusAction}.confirm`)}
        confirmColor={statusAction === 'disable' ? 'orange' : 'green'}
        pending={setMemberEnabled.isPending}
        error={setMemberEnabled.error}
        onConfirm={confirmStatus}
        onClose={closeStatusConfirm}
      />

      <ConfirmModal
        opened={removeTarget !== null}
        title={t('members.removeTitle', { member: removeTarget ? personName(removeTarget) : '' })}
        message={t('members.removeMessage', { org })}
        confirmLabel={t('common.remove')}
        pending={removeMember.isPending}
        error={removeMember.error}
        onConfirm={confirmRemove}
        onClose={closeRemoveConfirm}
      />
    </Stack>
  );
}
