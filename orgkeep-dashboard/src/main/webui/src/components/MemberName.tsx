import { Badge, Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { OrgMember } from '../api/organisations';
import { personName } from '../pages/personName';

interface MemberNameProps {
  member: OrgMember;
}

// The name of a member, with a "Disabled" badge when they can no longer log in (enabled may be null: unknown),
// and an "Invitation pending" badge until they choose a password from the invitation email
export default function MemberName({ member }: MemberNameProps) {
  const { t } = useTranslation();

  return (
    <Group gap="xs">
      {personName(member)}
      {member.enabled === false && (
        <Badge color="gray" variant="light" size="sm">
          {t('organisations.status.disabled')}
        </Badge>
      )}
      {member.invitationPending && (
        <Badge color="yellow" variant="light" size="sm">
          {t('invitation.pending')}
        </Badge>
      )}
    </Group>
  );
}