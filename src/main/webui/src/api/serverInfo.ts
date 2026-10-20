import { useQuery } from '@tanstack/react-query';

// Mirrors ServerInfoResource.ServerInfo on the Java side.
// Written by hand for now: to be replaced by types generated from /q/openapi.
export interface ServerInfo {
  application: string;
  version: string;
}

async function fetchServerInfo(): Promise<ServerInfo> {
  const response = await fetch('/api/info');
  if (!response.ok) {
    throw new Error(`GET /api/info failed with status ${response.status}`);
  }
  return response.json();
}

// TanStack Query calls fetchServerInfo, caches the result under the key
// and exposes loading / error states, so components never deal with fetch directly.
export function useServerInfo() {
  return useQuery({ queryKey: ['server-info'], queryFn: fetchServerInfo });
}
