import { Badge, Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { OrgMember } from '../api/organisations';
import { personName } from '../pages/personName';

interface MemberNameProps {
  member: OrgMember;
}

// The name of a member, with a "Disabled" badge when they can no longer log in (enabled may be null: unknown)
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
    </Group>
  );
}