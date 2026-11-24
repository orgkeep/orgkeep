import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteRequest, getJson, putJson } from './http';

// The SMTP server of the realm (SmtpResource in orgkeep-core): the server Keycloak sends every email of the realm
// through (invitations, password resets, email verifications). Mirrors org.orgkeep.api.model.SmtpServer.
export interface SmtpServer {
  host: string | null;
  port: number | null;
  from: string | null;
  fromDisplayName: string | null;
  // set only when authentication is on; null otherwise, even if a username was typed before turning it off
  username: string | null;
  // true when a password is stored; the password itself never leaves the core
  passwordSet: boolean;
  starttls: boolean;
  ssl: boolean;
  // true while orgkeep.smtp.host (ORGKEEP_SMTP_HOST) sets this realm's server: PUT and DELETE then answer 409
  managedByConfiguration: boolean;
}

// password left out of the request = keep the stored password (see SmtpPage); a value replaces it, ignored
// without username. fromDisplayName and username left out or blank = removed (no authentication for username).
export interface UpdateSmtpRequest {
  host: string;
  port: number;
  from: string;
  fromDisplayName?: string;
  username?: string;
  password?: string;
  starttls: boolean;
  ssl: boolean;
}

const SMTP_KEY = 'smtp';
const SMTP_URL = '/api/admin/smtp';

export function useSmtp() {
  return useQuery({
    queryKey: [SMTP_KEY],
    queryFn: () => getJson<SmtpServer>(SMTP_URL),
  });
}

export function useUpdateSmtp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateSmtpRequest) => putJson<SmtpServer>(SMTP_URL, request),
    onSuccess: (saved) => queryClient.setQueryData([SMTP_KEY], saved),
  });
}

export function useDeleteSmtp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => deleteRequest(SMTP_URL),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [SMTP_KEY] }),
  });
}
