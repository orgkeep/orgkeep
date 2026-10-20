import type { TFunction } from 'i18next';
import type { CurrentUser } from '../api/currentUser';
import { administeredOrgs, isRealmOwner } from '../auth/roles';

export interface MenuItem {
  label: string;
  path: string;
}

// A group of links in the left menu, under an optional title.
export interface MenuSection {
  title: string | null;
  items: MenuItem[];
}

// The left menu of a user: one section per thing the user can manage.
// A plain function of the user, so it is easy to read (and to test) on its own:
// the translation function is passed in rather than read from a hook, which only works inside a component.
export function buildMenu(user: CurrentUser, t: TFunction): MenuSection[] {
  const sections: MenuSection[] = [{ title: null, items: [{ label: t('menu.home'), path: '/' }] }];

  if (isRealmOwner(user)) {
    sections.push({
      title: t('menu.realm'),
      items: [{ label: t('menu.organisations'), path: '/organisations' }],
    });
  }

  for (const org of administeredOrgs(user)) {
    sections.push({
      title: t('menu.organisation', { org }),
      items: [{ label: t('menu.members'), path: `/orgs/${org}/members` }],
    });
  }

  return sections;
}
