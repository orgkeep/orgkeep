// Query keys shared by several files of src/api/. The teams and the service accounts refresh each other, and
// organisations.ts refreshes the service accounts: if each file exported its own key, they would import each other
// in a circle. A small file with no import breaks the circle.
export const TEAMS_KEY = 'teams';
export const SERVICE_ACCOUNTS_KEY = 'serviceAccounts';
