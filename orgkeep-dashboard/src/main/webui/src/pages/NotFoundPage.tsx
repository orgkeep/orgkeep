import { Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export default function NotFoundPage() {
  const { t } = useTranslation();

  return (
    <>
      <Title order={2}>{t('notFound.title')}</Title>
      <Text c="dimmed">{t('notFound.text')}</Text>
    </>
  );
}
