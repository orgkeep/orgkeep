import { Button, CopyButton } from '@mantine/core';
import { useTranslation } from 'react-i18next';

// A small "Copy" button next to a value a developer pastes into their configuration.
// Mantine's CopyButton passes "copy" (the click handler) and "copied" (true for a moment after the click)
// to the function inside it, which draws the button
export default function CopyValue({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <CopyButton value={value}>
      {({ copied, copy }) => (
        <Button variant="subtle" size="compact-xs" color={copied ? 'green' : 'gray'} onClick={copy}>
          {copied ? t('common.copied') : t('common.copy')}
        </Button>
      )}
    </CopyButton>
  );
}
