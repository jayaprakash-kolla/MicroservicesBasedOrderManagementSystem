# Showcase site

A single-page site that explains the order management system and lets anyone run an order through the saga.

It has two modes:

- **In this browser** (default, and the only mode on the published site). An in-browser model of order, inventory and payment service runs the saga with a short pause between steps. Every rule is a port of a named Java method (see `src/api/simulation.ts`), and `src/api/simulation.test.ts` checks it reaches the same outcomes, failure reasons and stock levels as the Java services.
- **Real services.** When you run the site with `npm run dev` and the backend is up, it detects the gateway and drives the real Spring Boot services. Kafka messages come from `saga-monitor` over Server-Sent Events.

## Run it

Needs Node.js 22.12 or newer.

```bash
npm install
npm run dev        # http://localhost:15173
```

With the backend running (see the root README), the page switches to **Real services** on its own. If a service is still registering with Eureka, wait a few seconds and press **Check again**.

The dev server forwards API calls, so the browser only ever talks to its own origin and the gateway needs no CORS configuration:

| Path | Forwarded to |
|---|---|
| `/api/v1/saga/*` | saga-monitor, `http://localhost:18084` |
| `/api/v1/*` | api-gateway, `http://localhost:18080` |

If you changed a port locally, override it: `GATEWAY_URL=http://localhost:19099 npm run dev` (also `SAGA_MONITOR_URL`).

## Other commands

```bash
npm test           # simulation tests (Vitest)
npm run build      # static site in dist/
npm run preview    # serve dist/ locally, with the same API forwarding
npm run lint
```

## Publish to GitHub Pages

`.github/workflows/deploy-frontend.yml` builds and deploys the site on every push to `main` that touches `frontend/`. Enable it once in the repository: **Settings → Pages → Build and deployment → Source: GitHub Actions**. The site is then at `https://<user>.github.io/<repository>/`.

The published site is static, so it always runs in simulation mode.

## Personal details

Name, links and an optional demo-video link are in `src/site.ts`.

## Layout

```
src/
├── api/          backend interface, simulation, live client, shared types
├── saga/         topic table (who publishes, who reads) and event descriptions
├── hooks/        useSagaConsole: state, polling and the event stream
├── components/   swimlane diagram, status track, order form, stock and ledgers
└── sections/     the page, top to bottom
```
