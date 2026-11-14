import { ActionIcon, CopyButton, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';

// A small copy icon next to a value a developer pastes into their configuration.
// Mantine's CopyButton passes "copy" (the click handler) and "copied" (true for a moment after the click)
// to the function inside it, which draws the button: two sheets, then a check mark once copied
export default function CopyValue({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <CopyButton value={value}>
      {({ copied, copy }) => (
        <Tooltip label={copied ? t('common.copied') : t('common.copy')} withArrow>
          {/* flexShrink 0: next to a long value in a row (a Group), the value shrinks and wraps, never the button.
              aria-label: the word the icon replaces, for screen readers */}
          <ActionIcon
            variant="subtle"
            color={copied ? 'green' : 'gray'}
            onClick={copy}
            aria-label={t('common.copy')}
            style={{ flexShrink: 0 }}
          >
            {copied ? <CheckIcon /> : <CopyIcon />}
          </ActionIcon>
        </Tooltip>
      )}
    </CopyButton>
  );
}

// Drawn inline, like GearIcon: stroke="currentColor" takes the color of the button
function CopyIcon() {
  return (
    <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="8" y="8" width="12" height="12" rx="2" />
      <path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12l5 5L20 7" />
    </svg>
  );
}
