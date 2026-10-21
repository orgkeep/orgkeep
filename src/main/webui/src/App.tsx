import { Alert, Center, Container, Loader } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Route, Routes } from 'react-router';
import { useCurrentUser } from './api/currentUser';
import { administeredOrgs, isRealmOwner } from './auth/roles';
import AppLayout from './layout/AppLayout';
import CatalogPage from './pages/CatalogPage';
import CatalogRolePage from './pages/CatalogRolePage';
import CatalogsPage from './pages/CatalogsPage';
import HomePage from './pages/HomePage';
import LoginClientPage from './pages/LoginClientPage';
import LoginPage from './pages/LoginPage';
import NotFoundPage from './pages/NotFoundPage';
import OrganisationPage from './pages/OrganisationPage';
import OrganisationsPage from './pages/OrganisationsPage';
import OrgMembersPage from './pages/OrgMembersPage';
import OrgTeamsPage from './pages/OrgTeamsPage';
import ProductPage from './pages/ProductPage';
import TeamPage from './pages/TeamPage';

export default function App() {
  // t(key) returns the text of the current language, like a Spring MessageSource.getMessage(key)
  const { t } = useTranslation();
  // the hook re-renders this component when the request state changes: pending -> data or error
  const { data: user, isPending, error } = useCurrentUser();

  if (isPending) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (error) {
    return (
      <Container size="sm" py="xl">
        <Alert color="red" title={t('app.backendUnreachable')}>
          {error.message}
        </Alert>
      </Container>
    );
  }
  if (!user.authenticated) {
    return <LoginPage />;
  }

  // The routes: URL path -> page, like the @Path of JAX-RS resources but on the browser side.
  // Same role checks as the menu (layout/menu.ts): a page missing from the menu is not routed either.
  return (
    <AppLayout user={user}>
      <Routes>
        <Route path="/" element={<HomePage user={user} />} />
        {isRealmOwner(user) && <Route path="/organisations" element={<OrganisationsPage />} />}
        {/* :org is a path parameter, read by the page with useParams() */}
        {isRealmOwner(user) && <Route path="/organisations/:org" element={<OrganisationPage />} />}
        {/* the same page as the org admins, without the actions: the realm owner looks, the org admins manage */}
        {isRealmOwner(user) && <Route path="/organisations/:org/teams/:team" element={<TeamPage readOnly />} />}
        {isRealmOwner(user) && <Route path="/catalogs" element={<CatalogsPage />} />}
        {isRealmOwner(user) && <Route path="/catalogs/:catalog" element={<CatalogPage />} />}
        {isRealmOwner(user) && <Route path="/catalogs/:catalog/products/:product" element={<ProductPage />} />}
        {isRealmOwner(user) && <Route path="/catalogs/:catalog/roles/:role" element={<CatalogRolePage />} />}
        {isRealmOwner(user) && <Route path="/login-client" element={<LoginClientPage />} />}
        {administeredOrgs(user).length > 0 && <Route path="/orgs/:org/members" element={<OrgMembersPage />} />}
        {administeredOrgs(user).length > 0 && <Route path="/orgs/:org/teams" element={<OrgTeamsPage />} />}
        {administeredOrgs(user).length > 0 && <Route path="/orgs/:org/teams/:team" element={<TeamPage />} />}
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AppLayout>
  );
}
