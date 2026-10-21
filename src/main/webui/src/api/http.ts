// The ways to call the API, shared by the files of src/api/. Every call of the front goes through here:
// - the session cookie is sent automatically (same origin);
// - every call carries X-Requested-With: JavaScript. The backend refuses a call authenticated by the session cookie
//   without it (CSRF protection: a page of another site cannot add this header, see CsrfFilter on the Java side);
// - the error message of the API is put in the Error, so a component can show error.message as is.

const CSRF_HEADERS = { 'X-Requested-With': 'JavaScript' };

// Quarkus answers 499 instead of a redirect to Keycloak when the session expired during a fetch (a fetch cannot
// follow a redirect to another origin). See quarkus.oidc.dashboard.authentication.java-script-auto-redirect.
export const SESSION_EXPIRED = 499;

export class SessionExpiredError extends Error {
  constructor() {
    super('Session expired');
  }
}

// The two error bodies of the API:
// - {"message": "..."} for its business errors (404, 409) and the CSRF refusal;
// - the Bean Validation report of an invalid request (400):
//   {"violations": [{"field": "create.request.name", "message": "..."}]}
interface ErrorBody {
  message?: string;
  violations?: { field?: string; message?: string }[];
}

// "create.request.name" (method.parameter.field) -> "name"
function fieldName(path: string | undefined): string {
  return path ? path.substring(path.lastIndexOf('.') + 1) : '';
}

async function errorOf(response: Response): Promise<Error> {
  if (response.status === SESSION_EXPIRED) {
    return new SessionExpiredError();
  }
  try {
    const body: ErrorBody = await response.json();
    if (typeof body.message === 'string') {
      return new Error(body.message);
    }
    if (body.violations && body.violations.length > 0) {
      return new Error(body.violations.map((v) => `${fieldName(v.field)}: ${v.message}`).join(', '));
    }
  } catch {
    // no JSON body (e.g. a 403 of Quarkus): fall back to the status below
  }
  return new Error(`${response.status} ${response.statusText}`);
}

// One place for every method: the body, when there is one, is sent as JSON
async function request(method: string, url: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { ...CSRF_HEADERS };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw await errorOf(response);
  }
  return response;
}

export async function getJson<T>(url: string): Promise<T> {
  return (await request('GET', url)).json();
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  return (await request('POST', url, body)).json();
}

export async function putJson<T>(url: string, body: unknown): Promise<T> {
  return (await request('PUT', url, body)).json();
}

// A PUT without body, answered 204 No Content (e.g. putting a member in a team): nothing to parse, hence void
export async function putRequest(url: string): Promise<void> {
  await request('PUT', url);
}

// The backend answers 204 No Content, or the changed resource that nobody reads: nothing to parse, hence void
export async function deleteRequest(url: string): Promise<void> {
  await request('DELETE', url);
}
