import type { SagaEvent, ServiceName } from '../api/types'
import { formatAmount } from '../format'
import { baseTopic, topicInfo } from './topics'

export type EventKind = 'forward' | 'failure' | 'compensation'

export interface EventDescription {
  kind: EventKind
  publishedBy: ServiceName
  consumedBy: ServiceName[]
  /** One plain sentence: what the event says and what its consumers do about it. */
  summary: string
}

function field(event: SagaEvent, name: string): unknown {
  return typeof event.payload === 'object' && event.payload !== null ? event.payload[name] : undefined
}

function text(event: SagaEvent, name: string): string {
  const value = field(event, name)
  return value === undefined || value === null ? '' : String(value)
}

function amount(event: SagaEvent, name: string): string {
  const value = Number(field(event, name))
  return Number.isFinite(value) ? formatAmount(value) : ''
}

function itemsSummary(event: SagaEvent): string {
  const items = field(event, 'items')
  if (!Array.isArray(items)) return ''
  return items
    .map((line: { skuCode?: string; quantity?: number }) => `${line.quantity} × ${line.skuCode}`)
    .join(', ')
}

/**
 * Turns a saga event into what the swimlane shows. Works on the same JSON in
 * simulation and live mode, because both carry the Java event records.
 */
export function describeEvent(event: SagaEvent): EventDescription | null {
  const info = topicInfo(event.topic)
  if (!info) return null

  let kind: EventKind = 'forward'
  let summary: string

  switch (baseTopic(event.topic)) {
    case 'order-created':
      summary = `Order ${text(event, 'orderNumber')} saved as PENDING: ${itemsSummary(event)}, total ${amount(event, 'totalAmount')}. Inventory checks every line before holding anything.`
      break
    case 'inventory-reserved':
      summary = `Stock is held. Order moves to INVENTORY_RESERVED, and payment charges ${amount(event, 'totalAmount')}. Both read their own copy of this event.`
      break
    case 'inventory-failed':
      kind = 'failure'
      summary = `${text(event, 'reason')}. Nothing was held, so order cancels with nothing to undo.`
      break
    case 'payment-completed':
      summary = `Charged ${amount(event, 'amount')} as ${text(event, 'paymentId')}. Order is CONFIRMED and inventory turns the hold into a sale.`
      break
    case 'payment-failed':
      kind = 'failure'
      summary = `${text(event, 'reason')}. The stock is still held in another database, so order cancels and asks for it back.`
      break
    case 'order-cancelled':
      kind = 'compensation'
      summary = 'Compensating transaction: inventory releases every hold for this order, so the stock is available again.'
      break
    default:
      return null
  }

  if (event.deadLetter) {
    kind = 'failure'
    summary = `A consumer failed on this message three times, so it was moved to ${event.topic} instead of blocking the partition.`
  }

  return { kind, publishedBy: info.publishedBy, consumedBy: info.consumedBy, summary }
}

/** Sorts by publish time, then by arrival order. */
export function chronological(a: SagaEvent, b: SagaEvent): number {
  const byTime = new Date(a.occurredAt).getTime() - new Date(b.occurredAt).getTime()
  return byTime !== 0 ? byTime : a.sequence - b.sequence
}
