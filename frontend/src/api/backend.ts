import type { Order, Payment, PlaceOrderRequest, SagaEvent, Stock } from './types'

export type BackendMode = 'simulation' | 'live'

/** Whether the live feed of Kafka events is flowing. */
export type StreamState = 'connecting' | 'open' | 'unavailable'

/**
 * Everything the page needs from "the system". Two implementations exist:
 * SimulatedBackend runs the saga inside the browser, LiveBackend talks to the
 * real gateway (port 18080) and saga-monitor (port 18084) through the dev proxy.
 */
export interface SagaBackend {
  readonly mode: BackendMode

  placeOrder(request: PlaceOrderRequest): Promise<Order>
  getOrder(id: number): Promise<Order>
  listOrders(): Promise<Order[]>

  listStock(): Promise<Stock[]>
  addStock(skuCode: string, quantity: number): Promise<Stock>

  listPayments(): Promise<Payment[]>

  /** Recently observed events, oldest first, optionally for one order. */
  recentEvents(orderId?: number): Promise<SagaEvent[]>

  /** Subscribes to every saga event as it is published. Returns an unsubscribe function. */
  onEvent(listener: (event: SagaEvent) => void): () => void

  /** Subscribes to the state of the event feed. Returns an unsubscribe function. */
  onStreamState(listener: (state: StreamState) => void): () => void

  /** Releases timers and connections. */
  close(): void
}
