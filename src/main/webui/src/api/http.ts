// The two ways to call the backend, shared by the files of src/api/.
// Every error answer of /api is {"message": "..."} (see CoreErrorMappers on the Java side):
// the message is put in the Error, so a component can show error.message as is.

async function errorOf(response: Response): Promise<Error> {
  try {
    const body = await response.json();
    if (typeof body.message === 'string') {
      return new Error(body.message);
    }
  } catch {
    // no JSON body (e.g. a 403 of Quarkus): fall back to the status below
  }
  return new Error(`${response.status} ${response.statusText}`);
}

export async function getJson<T>(url: string): Promise<T> {
  // the session cookie is sent automatically: same origin
  const response = await fetch(url);
  if (!response.ok) {
    throw await errorOf(response);
  }
  return response.json();
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await errorOf(response);
  }
  return response.json();
}

export async function putJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await errorOf(response);
  }
  return response.json();
}

// The backend answers 204 No Content: nothing to read, hence Promise<void>
export async function deleteRequest(url: string): Promise<void> {
  const response = await fetch(url, { method: 'DELETE' });
  if (!response.ok) {
    throw await errorOf(response);
  }
}
