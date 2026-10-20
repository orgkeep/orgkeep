import '@mantine/core/styles.css';
// initializes the translations before any component renders
import './i18n';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import App from './App';

// Entry point: wires the "application context" once, a bit like a Spring @Configuration.
// MantineProvider gives the UI theme, QueryClientProvider gives the API cache to every component,
// BrowserRouter follows the URL of the browser to pick the page (see the routes in App.tsx).
const queryClient = new QueryClient();

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
