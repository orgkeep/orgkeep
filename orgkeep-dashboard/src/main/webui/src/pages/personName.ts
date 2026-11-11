// What the core sends about a user (OrgMember), at least the fields needed for a display name
interface Person {
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
}

// "Bob Durand", or the email (else the username) when the user was created without a name
export function personName(person: Person): string {
  const fullName = [person.firstName, person.lastName].filter(Boolean).join(' ');
  return fullName || person.email || person.username;
}
