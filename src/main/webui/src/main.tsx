import '@mantine/core/styles.css';
// initializes the translations before any component renders
import './i18n';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { SessionExpiredError } from './api/http';
import App from './App';

// The session expired (the backend answered 499): log in again. A full page navigation, not a fetch: the backend
// redirects to Keycloak, which often logs the user back in at once (its own session lasts longer), then comes back.
function onApiError(error: Error) {
  if (error instanceof SessionExpiredError) {
    window.location.assign('/api/auth/login');
  }
}

// Entry point: wires the "application context" once, a bit like a Spring @Configuration.
// MantineProvider gives the UI theme, QueryClientProvider gives the API cache to every component,
// BrowserRouter follows the URL of the browser to pick the page (see the routes in App.tsx).
const queryClient = new QueryClient({
  // called for the error of every query and every mutation, like a global exception handler
  queryCache: new QueryCache({ onError: onApiError }),
  mutationCache: new MutationCache({ onError: onApiError }),
  defaultOptions: {
    // retrying would only get 499 again
    queries: { retry: (failureCount, error) => !(error instanceof SessionExpiredError) && failureCount < 3 },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
);
