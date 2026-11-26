import { Badge, Stack, Text, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { Suspension } from '../api/organisations';

interface SuspensionBadgeProps {
  suspension: Suspension;
  // true on the realm owner's screens: shows who suspended. Org admins never see it (the core does not send it to them)
  realmOwner?: boolean;
}

// "Suspended by provider", distinct from the plain "Disabled" badge: only the realm owner sets and lifts this.
// The reason and the date are in a tooltip, who suspended too when realmOwner is set. The date line is skipped
// when suspendedAt is null (the core could not parse the underlying attribute, set by a console edit).
export default function SuspensionBadge({ suspension, realmOwner = false }: SuspensionBadgeProps) {
  const { t, i18n } = useTranslation();
  const date = suspension.suspendedAt ? new Date(suspension.suspendedAt).toLocaleString(i18n.language) : null;

  return (
    <Tooltip
      multiline
      w={260}
      label={
        <Stack gap={2}>
          <Text size="xs">{suspension.reason}</Text>
          {date && (
            <Text size="xs" c="dimmed">
              {t('suspension.since', { date })}
            </Text>
          )}
          {realmOwner && suspension.suspendedBy && (
            <Text size="xs" c="dimmed">
              {t('suspension.by', { who: suspension.suspendedBy })}
            </Text>
          )}
        </Stack>
      }
    >
      <Badge color="red" variant="light" style={{ cursor: 'help' }}>
        {t('suspension.badge')}
      </Badge>
    </Tooltip>
  );
}
