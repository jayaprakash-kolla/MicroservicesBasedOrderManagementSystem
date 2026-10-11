import type { Order, SagaEvent } from '../api/types'

type StepState = 'done' | 'current' | 'skipped' | 'upcoming' | 'failed'

interface Step {
  label: string
  state: StepState
  note: string
}

/**
 * The order's status as order-service sees it (OrderStatus.java):
 *
 *   PENDING -> INVENTORY_RESERVED -> CONFIRMED
 *      |               |
 *      +---------> CANCELLED <-+
 *
 * Polling can miss INVENTORY_RESERVED because it often lasts milliseconds, so
 * the events are used too: if inventory-reserved was published, that step
 * happened even if no poll caught it.
 */
function stepsFor(order: Order, events: SagaEvent[]): Step[] {
  const sawReserved = events.some((event) => event.topic === 'inventory-reserved')
  const sawInventoryFailure = events.some((event) => event.topic === 'inventory-failed')
  const finished = order.status === 'CONFIRMED' || order.status === 'CANCELLED'

  const reservedReached =
    sawReserved ||
    order.status === 'INVENTORY_RESERVED' ||
    order.status === 'CONFIRMED' ||
    (order.status === 'CANCELLED' && (order.failureReason ?? '').startsWith('Card declined'))

  const reservedSkipped =
    order.status === 'CANCELLED' && !reservedReached && (sawInventoryFailure || order.failureReason !== null)

  return [
    {
      label: 'PENDING',
      state: order.status === 'PENDING' ? 'current' : 'done',
      note: 'saved, waiting on inventory',
    },
    {
      label: 'INVENTORY_RESERVED',
      state: order.status === 'INVENTORY_RESERVED'
        ? 'current'
        : reservedSkipped
          ? 'skipped'
          : reservedReached
            ? 'done'
            : 'upcoming',
      note: reservedSkipped ? 'never reached' : 'stock held, waiting on payment',
    },
    finished
      ? {
          label: order.status,
          state: order.status === 'CONFIRMED' ? 'done' : 'failed',
          note: order.status === 'CONFIRMED' ? 'paid, stock sold' : 'saga unwound',
        }
      : { label: 'CONFIRMED or CANCELLED', state: 'upcoming', note: 'decided by the next event' },
  ]
}

export function StatusTrack({ order, events }: { order: Order; events: SagaEvent[] }) {
  const steps = stepsFor(order, events)
  return (
    <ol className="status-track" aria-label="Order status">
      {steps.map((step) => (
        <li key={step.label} className="status-track__step" data-state={step.state}>
          {/* Allow a line break after each underscore so long statuses wrap on phones. */}
          <code className="status-track__label">{step.label.replaceAll('_', '_\u200b')}</code>
          <span className="status-track__note">{step.note}</span>
        </li>
      ))}
    </ol>
  )
}
