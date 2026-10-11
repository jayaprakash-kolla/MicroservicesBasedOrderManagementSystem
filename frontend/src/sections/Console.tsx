import { useCallback, useEffect, useRef, useState } from 'react'
import type { BackendMode, SagaBackend, StreamState } from '../api/backend'
import { SCENARIOS } from '../api/catalog'
import { LiveBackend, type ProbeResult } from '../api/live'
import { SimulatedBackend } from '../api/simulation'
import type { Order } from '../api/types'
import { OrderForm } from '../components/OrderForm'
import { OrderHistory } from '../components/OrderHistory'
import { PaymentsList } from '../components/PaymentsList'
import { StatusTrack } from '../components/StatusTrack'
import { StockTable } from '../components/StockTable'
import { Swimlane } from '../components/Swimlane'
import { formatAmount, formatClock } from '../format'
import { useSagaConsole } from '../hooks/useSagaConsole'

/** Live mode only exists where a dev server can proxy /api: `npm run dev` on your laptop. */
const LIVE_SUPPORTED = import.meta.env.DEV || import.meta.env.VITE_ENABLE_LIVE === 'true'

type ProbeState = 'idle' | 'checking' | ProbeResult

interface Session {
  id: number
  backend: SagaBackend
}

export function Console() {
  const [probe, setProbe] = useState<ProbeState>(LIVE_SUPPORTED ? 'checking' : 'idle')
  const [session, setSession] = useState<Session>(() => ({ id: 1, backend: new SimulatedBackend() }))
  const sessionRef = useRef(session)

  /** Swaps backends. The workspace below is keyed by session, so it starts clean each time. */
  const switchTo = useCallback((mode: BackendMode) => {
    const current = sessionRef.current
    if (current.backend.mode === mode) return
    current.backend.close()
    const next: Session = {
      id: current.id + 1,
      backend: mode === 'live' ? new LiveBackend() : new SimulatedBackend(),
    }
    sessionRef.current = next
    setSession(next)
  }, [])

  const probeLive = useCallback(async () => {
    const result = await LiveBackend.probe()
    setProbe(result)
    switchTo(result === 'ok' ? 'live' : 'simulation')
  }, [switchTo])

  useEffect(() => {
    // Running locally with the services up? Then show the real thing by default.
    // State is only set after the network call resolves, not synchronously.
    // oxlint-disable-next-line react/set-state-in-effect
    if (LIVE_SUPPORTED) void probeLive()
  }, [probeLive])

  useEffect(() => () => sessionRef.current.backend.close(), [])

  const recheck = () => {
    setProbe('checking')
    void probeLive()
  }

  return (
    <section id="demo" className="section console" aria-labelledby="demo-title">
      <div className="section__intro">
        <h2 id="demo-title" className="section__title">Run an order through it</h2>
        <p className="section__lede">
          Pick a scenario. Each row of the diagram is one Kafka message: the dot is the service that published it,
          the arrowheads are the services that read it.
        </p>
      </div>

      <Workspace
        key={session.id}
        backend={session.backend}
        probe={probe}
        onChoose={(mode) => (mode === 'live' ? recheck() : switchTo('simulation'))}
        onRetry={recheck}
      />
    </section>
  )
}

interface WorkspaceProps {
  backend: SagaBackend
  probe: ProbeState
  onChoose: (mode: BackendMode) => void
  onRetry: () => void
}

function Workspace({ backend, probe, onChoose, onRetry }: WorkspaceProps) {
  const saga = useSagaConsole(backend)
  const skus = saga.stock.map((item) => item.skuCode)
  const mode = backend.mode

  return (
    <>
      <ModeBar
        mode={mode}
        probe={probe}
        streamState={saga.streamState}
        onChoose={onChoose}
        onRetry={onRetry}
        onReset={saga.reset}
      />

      <div className="console__grid">
        <div className="console__controls">
          <h3 className="panel-title">Scenarios</h3>
          <ul className="scenarios">
            {SCENARIOS.map((scenario) => (
              <li key={scenario.id}>
                <button
                  type="button"
                  className="scenario"
                  data-scenario={scenario.id}
                  disabled={saga.placing}
                  onClick={() => void saga.placeOrder(scenario.request)}
                >
                  <span className="scenario__name">{scenario.name}</span>
                  <code className="scenario__order">{scenario.summary}</code>
                  <span className="scenario__outcome">{scenario.outcome}</span>
                </button>
              </li>
            ))}
          </ul>

          <details className="custom-order">
            <summary>Build your own order</summary>
            <OrderForm
              skus={skus}
              disabled={saga.placing}
              serverFields={saga.error?.fields ?? {}}
              onSubmit={(request) => void saga.placeOrder(request)}
            />
          </details>
        </div>

        <div className="console__saga" aria-live="polite">
          {saga.error && (
            <div className="notice" role="alert">
              <p>{saga.error.message}</p>
              <button type="button" className="link-button" onClick={saga.clearError}>Dismiss</button>
            </div>
          )}

          {saga.activeOrder ? (
            <OrderHeader order={saga.activeOrder} />
          ) : (
            <p className="console__placeholder">No order selected.</p>
          )}
          {saga.activeOrder && <StatusTrack order={saga.activeOrder} events={saga.activeEvents} />}

          <Swimlane
            events={saga.activeEvents}
            emptyState={<SwimlaneEmpty mode={mode} streamState={saga.streamState} hasOrder={saga.activeOrder !== null} />}
          />
        </div>
      </div>

      <div className="console__state">
        <div className="state-panel">
          <h3 className="panel-title">Stock <span className="panel-title__owner">inventory-service</span></h3>
          <StockTable stock={saga.stock} onRestock={(sku) => void saga.addStock(sku, 10)} />
        </div>
        <div className="state-panel">
          <h3 className="panel-title">Charges <span className="panel-title__owner">payment-service</span></h3>
          <PaymentsList payments={saga.payments} />
        </div>
        <div className="state-panel">
          <h3 className="panel-title">Orders <span className="panel-title__owner">order-service</span></h3>
          <OrderHistory orders={saga.orders} activeOrderId={saga.activeOrder?.id ?? null} onSelect={saga.selectOrder} />
        </div>
      </div>
    </>
  )
}

function OrderHeader({ order }: { order: Order }) {
  return (
    <div className="order-header">
      <h3 className="order-header__title">
        <code>{order.orderNumber}</code>
      </h3>
      <p className="order-header__meta">
        Order {order.id} for <code>{order.customerId}</code>, total {formatAmount(order.totalAmount)}, placed at{' '}
        {formatClock(order.createdAt)}
      </p>
      {order.failureReason && <p className="order-header__reason">{order.failureReason}</p>}
    </div>
  )
}

function SwimlaneEmpty({ mode, streamState, hasOrder }: { mode: BackendMode; streamState: StreamState; hasOrder: boolean }) {
  if (!hasOrder) {
    return <p>Run a scenario and its messages appear here, one row per event.</p>
  }
  if (mode === 'live' && streamState === 'unavailable') {
    return (
      <p>
        The order's status is still followed by polling, but saga-monitor is not running, so there is no feed of
        Kafka messages to draw. Start it on port 18084 and new orders will show every step.
      </p>
    )
  }
  return <p>Waiting for the first message.</p>
}

interface ModeBarProps {
  mode: BackendMode
  probe: ProbeState
  streamState: StreamState
  onChoose: (mode: BackendMode) => void
  onRetry: () => void
  onReset: (() => void) | null
}

const STREAM_TEXT: Record<StreamState, string> = {
  open: 'connected',
  connecting: 'connecting',
  unavailable: 'not running',
}

function ModeBar({ mode, probe, streamState, onChoose, onRetry, onReset }: ModeBarProps) {
  let note
  if (mode === 'live') {
    note = (
      <>
        Connected to api-gateway on port 18080. Event feed from saga-monitor on port 18084:{' '}
        <strong data-stream={streamState}>{STREAM_TEXT[streamState]}</strong>.
      </>
    )
  } else if (!LIVE_SUPPORTED) {
    note = (
      <>
        The order, inventory and payment rules, running in your browser with a pause between steps so you can follow them. To drive
        the real Spring Boot services instead, <a href="#run-it">run this page locally</a>.
      </>
    )
  } else if (probe === 'checking') {
    note = <>Looking for api-gateway on port 18080.</>
  } else if (probe === 'not-registered') {
    note = (
      <>
        The gateway on port 18080 answered, but inventory-service has not registered with Eureka yet, so this is the
        in-browser model for now. Registration can take up to 30 seconds.{' '}
        <button type="button" className="link-button" onClick={onRetry}>Check again</button>
      </>
    )
  } else {
    note = (
      <>
        No gateway answered on port 18080, so this is the in-browser model of the services.{' '}
        <button type="button" className="link-button" onClick={onRetry}>Check again</button>
      </>
    )
  }

  return (
    <div className="mode-bar">
      <div className="mode-bar__switch" role="group" aria-label="Where the saga runs">
        <button type="button" aria-pressed={mode === 'simulation'} onClick={() => onChoose('simulation')}>
          In this browser
        </button>
        <button
          type="button"
          aria-pressed={mode === 'live'}
          disabled={!LIVE_SUPPORTED || probe === 'checking'}
          onClick={() => onChoose('live')}
        >
          Real services
        </button>
      </div>
      <p className="mode-bar__note">{note}</p>
      {onReset && (
        <button type="button" className="link-button mode-bar__reset" onClick={onReset}>
          Reset to seeded stock
        </button>
      )}
    </div>
  )
}
