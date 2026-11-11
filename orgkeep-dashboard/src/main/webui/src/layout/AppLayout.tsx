import type { ReactNode } from 'react';
import {AppShell, Burger, Button, Group, NavLink, Text, Image, Center} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import type { CurrentUser } from '../api/currentUser';
import { buildMenu } from './menu';
import banner from '../assets/orgkeep-banner.png';
import {useServerInfo} from "../api/serverInfo";

interface AppLayoutProps {
  user: CurrentUser;
  // the current page, chosen by the router in App.tsx
  children: ReactNode;
}

// A menu entry stays highlighted on its sub pages: "Organisations" on /organisations/acme too.
// Except Home ("/"), which every path starts with.
function isActive(itemPath: string, currentPath: string): boolean {
  if (itemPath === '/') {
    return currentPath === '/';
  }
  return currentPath === itemPath || currentPath.startsWith(`${itemPath}/`);
}

// The frame of every page once logged in: a header, the menu on the left, the page in the middle.
// Close to a layout template (Thymeleaf layout, JSF template) with a slot for the content.
export default function AppLayout({ user, children }: AppLayoutProps) {
  // on small screens the menu is hidden behind a "burger" button: this is its open / closed state
  const [menuOpened, { toggle: toggleMenu, close: closeMenu }] = useDisclosure();
  const location = useLocation();
  const { t } = useTranslation();
  const menu = buildMenu(user, t);

  const { data: serverInfo } = useServerInfo();

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !menuOpened } }}
      padding="md"
      footer={{height: 56}}
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group>
            <Burger opened={menuOpened} onClick={toggleMenu} hiddenFrom="sm" size="sm" />
            <Link to="/">
              <Image src={banner} alt="Orgkeep" h={44} w="auto"/>
            </Link>
          </Group>
          <Group>
            <Text size="sm">{user.fullName ?? user.username}</Text>
            {/* a full page navigation: the backend ends the session, logs out of Keycloak, then comes back to / */}
            <Button component="a" href="/api/auth/logout" variant="default" size="xs">
              {t('layout.logout')}
            </Button>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {menu.map((section, index) => (
          <div key={section.title ?? index}>
            {section.title && (
              <Text size="xs" fw={700} c="dimmed" tt="uppercase" px="sm" pt="md" pb={4}>
                {section.title}
              </Text>
            )}
            {section.items.map((item) => (
              // component={Link}: Mantine draws the link, React Router changes the page without a reload
              <NavLink
                key={item.path}
                component={Link}
                to={item.path}
                label={item.label}
                active={isActive(item.path, location.pathname)}
                onClick={closeMenu}
              />
            ))}
          </div>
        ))}
      </AppShell.Navbar>

      <AppShell.Main>{children}</AppShell.Main>
      <AppShell.Footer>
        <Center h="100%">
        {serverInfo && (
            <Text size="xs" c="dimmed">
              {serverInfo.application} {serverInfo.version}
            </Text>
        )}
        </Center>
      </AppShell.Footer>
    </AppShell>
  );
}
