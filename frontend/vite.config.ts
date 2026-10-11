import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/*
 * Ports come from each service's application.yml. Override them with
 * environment variables if you changed a port locally, e.g.
 *   GATEWAY_URL=http://localhost:19099 npm run dev
 */
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:18080'
const sagaMonitor = process.env.SAGA_MONITOR_URL ?? 'http://localhost:18084'

// The more specific prefix must come first: /api/v1/saga goes to saga-monitor,
// everything else under /api goes to the gateway.
const proxy = {
  '/api/v1/saga': { target: sagaMonitor, changeOrigin: true },
  '/api': { target: gateway, changeOrigin: true },
}

export default defineConfig({
  plugins: [react()],
  // Relative asset paths, so the built site works from any folder,
  // including https://<user>.github.io/<repository>/.
  base: './',
  server: { port: 15173, proxy },
  preview: { port: 4173, proxy },
})
