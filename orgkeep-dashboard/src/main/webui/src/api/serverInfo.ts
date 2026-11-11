import { useQuery } from '@tanstack/react-query';
import { getJson } from './http';

// Mirrors ServerInfoResource.ServerInfo on the Java side.
// Written by hand for now: to be replaced by types generated from /q/openapi.
export interface ServerInfo {
  application: string;
  version: string;
}

// TanStack Query calls the function, caches the result under the key
// and exposes loading / error states, so components never deal with fetch directly.
export function useServerInfo() {
  return useQuery({ queryKey: ['server-info'], queryFn: () => getJson<ServerInfo>('/api/info') });
}
