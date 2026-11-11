import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, Quarkus (Quinoa) starts this Vite server and proxies it on http://localhost:8284,
// so the app is opened through Quarkus and /api calls reach the Java backend.
export default defineConfig({
  plugins: [react()],
});
