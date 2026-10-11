import { useEffect, useState } from 'react'
import { SCENARIOS } from '../api/catalog'
import { SimulatedBackend } from '../api/simulation'
import type { OrderStatus, SagaEvent, Stock } from '../api/types'
import { Swimlane } from '../components/Swimlane'
import { formatCount } from '../format'
import { usePrefersReducedMotion } from '../hooks/usePrefersReducedMotion'
import { describeEvent } from '../saga/describe'
import { SITE } from '../site'

const DECLINED = SCENARIOS.find((scenario) => scenario.id === 'declined')!

export function Hero() {
  return (
    <header className="hero">
      <div className="hero__text">
        <h1 className="hero__title">An order that can undo itself</h1>
        <p className="hero__lede">
          Three Spring Boot services, each with its own database, that coordinate only through Kafka. When a card is
          declined after the stock is already held, no database rollback can reach across services, so the services
          undo the work themselves with a compensating transaction.
        </p>
        <div className="hero__actions">
          <a className="button" href="#demo">Run an order</a>
          <a className="button button--quiet" href={SITE.repositoryUrl} target="_blank" rel="noreferrer">
            Read the source
          </a>
        </div>
        <p className="hero__stack">
          Java 17, Spring Boot 3.2, Apache Kafka, Spring Cloud Gateway, Netflix Eureka, JUnit 5 with embedded Kafka.
        </p>
      </div>
      <HeroReplay />
    </header>
  )
}

/**
 * Plays the card-declined path on a loop: the one path where a step that has
 * already committed has to be undone. Uses the same simulation as the demo.
 */
function HeroReplay() {
  const reduced = usePrefersReducedMotion()
  const [events, setEvents] = useState<SagaEvent[]>([])
  const [laptop, setLaptop] = useState<Stock | null>(null)
  const [status, setStatus] = useState<OrderStatus | null>(null)

  useEffect(() => {
    const backend = new SimulatedBackend({ stepDelayMs: reduced ? 0 : 1400 })
    const stopEvents = backend.onEvent((event) => setEvents((current) => [...current, event]))

    let phase: 'waiting' | 'running' | 'holding' = 'waiting'
    let phaseStartedAt = Date.now()

    const tick = async () => {
      const [stock, orders] = await Promise.all([backend.listStock(), backend.listOrders()])
      const item = stock.find((row) => row.skuCode === 'LAPTOP-001') ?? null
      const order = orders[0] ?? null
      setLaptop(item)
      setStatus(order?.status ?? null)

      const now = Date.now()
      if (phase === 'waiting' && now - phaseStartedAt > (reduced ? 0 : 900)) {
        phase = 'running'
        phaseStartedAt = now
        await backend.placeOrder(DECLINED.request)
      } else if (phase === 'running' && order?.status === 'CANCELLED' && item?.reservedQuantity === 0) {
        phase = 'holding'
        phaseStartedAt = now
      } else if (phase === 'holding' && !reduced && now - phaseStartedAt > 5000) {
        backend.reset()
        setEvents([])
        phase = 'waiting'
        phaseStartedAt = now
      }
    }

    const interval = setInterval(() => void tick(), 200)
    void tick()

    return () => {
      clearInterval(interval)
      stopEvents()
      backend.close()
      setEvents([])
    }
  }, [reduced])

  const latest = events.at(-1)
  const latestSummary = latest ? describeEvent(latest)?.summary : null

  return (
    <div className="hero__replay">
      <Swimlane
        compact
        events={events}
        emptyState={<p>Placing 3 × LAPTOP-001 at 50,000 each.</p>}
        caption={
          <>
            <span className="replay__title">Card declined, replayed in your browser</span>
            <span className="replay__step">{latestSummary ?? 'An order over the payment limit is about to be placed.'}</span>
            {laptop && (
              <span className="replay__stock">
                <code>LAPTOP-001</code> {formatCount(laptop.availableQuantity)} available
                {laptop.reservedQuantity > 0 ? `, ${formatCount(laptop.reservedQuantity)} held` : ''}
                {status ? <>. Order is <code>{status}</code></> : null}
              </span>
            )}
          </>
        }
      />
    </div>
  )
}
