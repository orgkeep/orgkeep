import { Button, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { type OrgMember, useResendInvitation } from '../api/organisations';

interface ResendInvitationButtonProps {
  org: string;
  member: OrgMember;
}

// "Resend the invitation", only for a member who has not chosen a password yet.
// The hook is called here, once per row: each button has its own state (sending, sent, error), like a
// prototype-scoped bean rather than a singleton shared by the whole table.
export default function ResendInvitationButton({ org, member }: ResendInvitationButtonProps) {
  const { t } = useTranslation();
  const resend = useResendInvitation(org);

  if (!member.invitationPending) {
    return null;
  }
  if (resend.isSuccess) {
    return (
      <Text size="xs" c="teal">
        {t('invitation.sent')}
      </Text>
    );
  }
  return (
    <Stack gap={2} align="flex-end">
      <Button variant="subtle" size="xs" loading={resend.isPending} onClick={() => resend.mutate(member.id)}>
        {t('invitation.resend')}
      </Button>
      {/* the reason given by the core: no SMTP server on the realm, or a password already chosen */}
      {resend.error && (
        <Text size="xs" c="red" maw={260} ta="right">
          {resend.error.message}
        </Text>
      )}
    </Stack>
  );
}
