import { useState } from 'react';
import { Alert, Badge, Button, Center, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { type OrgMember, useOrgMembers, useRemoveOrgMember } from '../api/organisations';
import ConfirmModal from '../components/ConfirmModal';
import AddMemberModal from './AddMemberModal';
import { ROLE_COLORS } from './orgRoles';
import { personName } from './personName';
import MemberName from "../components/MemberName";

// The members of an org, managed by its org admins: add (the core sends the invitation) and remove.
export default function OrgMembersPage() {
  const { t } = useTranslation();
  // the :org part of the route /orgs/:org/members, like a @PathParam. Always set on this route, hence the "!"
  const org = useParams().org!;
  const { data: members, isPending, error } = useOrgMembers(org);
  const removeMember = useRemoveOrgMember(org);
  const [addOpened, { open: openAdd, close: closeAdd }] = useDisclosure();
  const [removeTarget, setRemoveTarget] = useState<OrgMember | null>(null);

  function closeRemoveConfirm() {
    removeMember.reset();
    setRemoveTarget(null);
  }

  function confirmRemove() {
    if (removeTarget) {
      removeMember.mutate(removeTarget.id, { onSuccess: closeRemoveConfirm });
    }
  }

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

  return (
    <Stack maw={1100}>
      <Group justify="space-between">
        <Title order={2}>{t('members.title', { org })}</Title>
        <Button onClick={openAdd}>{t('members.add')}</Button>
      </Group>

      {members.length === 0 ? (
        <Text c="dimmed">{t('members.empty')}</Text>
      ) : (
        <Table.ScrollContainer minWidth={640}>
          <Table highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('members.columns.name')}</Table.Th>
                <Table.Th>{t('members.columns.email')}</Table.Th>
                <Table.Th>{t('members.columns.role')}</Table.Th>
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
                    <Group justify="flex-end">
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

      <AddMemberModal org={org} opened={addOpened} onClose={closeAdd} />

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
