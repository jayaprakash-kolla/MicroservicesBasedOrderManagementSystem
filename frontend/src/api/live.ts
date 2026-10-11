import type { BackendMode, SagaBackend, StreamState } from './backend'
import { ApiError, type Order, type Payment, type PlaceOrderRequest, type SagaEvent, type Stock } from './types'

/*
 * Talks to the real system. All paths are relative, so the browser only ever
 * calls its own origin; the Vite dev server forwards them:
 *
 *   /api/v1/saga/*  -> saga-monitor  http://localhost:18084
 *   /api/v1/*       -> api-gateway   http://localhost:18080
 *
 * Same origin means no CORS configuration is needed on the gateway.
 */

export type ProbeResult = 'ok' | 'not-registered' | 'unreachable'

const STREAM_URL = '/api/v1/saga/stream'
const RECONNECT_MS = 5000

export class LiveBackend implements SagaBackend {
  readonly mode: BackendMode = 'live'

  private readonly eventListeners = new Set<(event: SagaEvent) => void>()
  private readonly stateListeners = new Set<(state: StreamState) => void>()
  private source: EventSource | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private state: StreamState = 'connecting'
  private closed = false

  /**
   * Checks whether the gateway answers and inventory-service is registered
   * behind it. A plain static host (GitHub Pages) has no /api, so this fails
   * there and the page stays in simulation mode.
   *
   *   'ok'               the gateway routed the request and got stock back
   *   'not-registered'   the gateway answered 503: no instance in Eureka yet
   *   'unreachable'      nothing useful answered on the gateway port
   */
  static async probe(timeoutMs = 2500): Promise<ProbeResult> {
    try {
      const response = await fetch('/api/v1/inventory', {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(timeoutMs),
      })
      if (response.status === 503) return 'not-registered'
      if (!response.ok) return 'unreachable'
      const body: unknown = await response.json()
      return Array.isArray(body) ? 'ok' : 'unreachable'
    } catch {
      return 'unreachable'
    }
  }

  placeOrder(request: PlaceOrderRequest): Promise<Order> {
    return this.request<Order>('/api/v1/orders', { method: 'POST', body: JSON.stringify(request) })
  }

  getOrder(id: number): Promise<Order> {
    return this.request<Order>(`/api/v1/orders/${id}`)
  }

  listOrders(): Promise<Order[]> {
    return this.request<Order[]>('/api/v1/orders')
  }

  listStock(): Promise<Stock[]> {
    return this.request<Stock[]>('/api/v1/inventory')
  }

  addStock(skuCode: string, quantity: number): Promise<Stock> {
    return this.request<Stock>('/api/v1/inventory', { method: 'POST', body: JSON.stringify({ skuCode, quantity }) })
  }

  listPayments(): Promise<Payment[]> {
    return this.request<Payment[]>('/api/v1/payments')
  }

  async recentEvents(orderId?: number): Promise<SagaEvent[]> {
    const query = orderId === undefined ? '?limit=200' : `?orderId=${orderId}&limit=50`
    try {
      return await this.request<SagaEvent[]>(`/api/v1/saga/events${query}`)
    } catch {
      // The monitor is optional; without it there is simply no event history.
      return []
    }
  }

  onEvent(listener: (event: SagaEvent) => void): () => void {
    this.eventListeners.add(listener)
    this.ensureStream()
    return () => this.eventListeners.delete(listener)
  }

  onStreamState(listener: (state: StreamState) => void): () => void {
    this.stateListeners.add(listener)
    listener(this.state)
    return () => this.stateListeners.delete(listener)
  }

  close(): void {
    this.closed = true
    this.source?.close()
    this.source = null
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.eventListeners.clear()
    this.stateListeners.clear()
  }

  // ------------------------------------------------------------------- stream

  private ensureStream(): void {
    if (this.source || this.closed || this.reconnectTimer) return

    const source = new EventSource(STREAM_URL)
    this.source = source
    this.setState('connecting')

    source.addEventListener('connected', () => this.setState('open'))
    source.addEventListener('open', () => this.setState('open'))
    source.addEventListener('saga-event', (message) => {
      try {
        const event = JSON.parse((message as MessageEvent<string>).data) as SagaEvent
        this.eventListeners.forEach((listener) => listener(event))
      } catch {
        // A malformed message should not break the page.
      }
    })
    source.addEventListener('error', () => {
      // EventSource retries a dropped connection by itself, but gives up for
      // good on an error response (monitor not running). Retry manually then.
      if (source.readyState === EventSource.CLOSED) {
        this.source = null
        this.setState('unavailable')
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null
          this.ensureStream()
        }, RECONNECT_MS)
      } else {
        this.setState('connecting')
      }
    })
  }

  private setState(state: StreamState): void {
    if (this.state === state) return
    this.state = state
    this.stateListeners.forEach((listener) => listener(state))
  }

  // ------------------------------------------------------------------- HTTP

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    let response: Response
    try {
      response = await fetch(path, {
        ...init,
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...init.headers },
      })
    } catch {
      throw new ApiError(0, 'NETWORK', 'The frontend could not reach its own dev server.')
    }

    if (response.ok) {
      return (await response.json()) as T
    }

    const body = await response.json().catch(() => null) as
      | { error?: string; message?: string; fields?: Record<string, string> }
      | null

    if (response.status === 503) {
      throw new ApiError(503, 'SERVICE_UNAVAILABLE',
        'The gateway is up, but the service behind this route has not registered with Eureka yet. Check http://localhost:18761.')
    }
    if (response.status === 502 || response.status === 504 || (response.status === 500 && body === null)) {
      throw new ApiError(response.status, 'GATEWAY_UNREACHABLE',
        'Nothing answered on the gateway port (18080). Start api-gateway and try again.')
    }
    throw new ApiError(
      response.status,
      body?.error ?? `HTTP_${response.status}`,
      body?.message ?? `The request failed with HTTP ${response.status}.`,
      body?.fields ?? {},
    )
  }
}
