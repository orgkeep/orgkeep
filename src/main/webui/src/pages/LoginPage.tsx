import {Button, Center, Image, Paper, Stack, Text} from '@mantine/core';
import { useTranslation } from 'react-i18next';
import logo from "../assets/logo.png";

// Shown when nobody is logged in. The username / password form itself is the Keycloak login page:
// the password never goes through the dashboard.
export default function LoginPage() {
  const { t } = useTranslation();

  return (
    <Center h="100vh">
      <Paper withBorder shadow="sm" p="xl" radius="md" w={360}>
        <Stack>
          <Image src={logo} alt="OrgKeep" h={150} w="auto" fit="contain"/>
          <Text size="sm" c="dimmed">
            {t('login.subtitle')}
          </Text>
          {/* a full page navigation, not a fetch: the backend redirects the browser to Keycloak,
              which sends it back here once logged in (see AuthResource) */}
          <Button component="a" href="/api/auth/login" fullWidth>
            {t('login.signIn')}
          </Button>
        </Stack>
      </Paper>
    </Center>
  );
}
