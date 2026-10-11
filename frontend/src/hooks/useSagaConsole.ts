import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { SagaBackend, StreamState } from '../api/backend'
import { SimulatedBackend } from '../api/simulation'
import {
  ApiError,
  eventKey,
  isFinished,
  type Order,
  type Payment,
  type PlaceOrderRequest,
  type SagaEvent,
  type Stock,
} from '../api/types'
import { chronological } from '../saga/describe'

const POLL_MS = 400
const POLL_GIVE_UP_MS = 60_000
const MAX_EVENTS = 1000
/**
 * H2 is in-memory, so order ids restart at 1 when order-service restarts while
 * Kafka still holds the old events. Events published well before an order was
 * created belong to an earlier run and are ignored.
 */
const STALE_EVENT_MS = 5_000

export interface SagaConsole {
  orders: Order[]
  stock: Stock[]
  payments: Payment[]
  activeOrder: Order | null
  activeEvents: SagaEvent[]
  streamState: StreamState
  placing: boolean
  error: ApiError | null
  placeOrder: (request: PlaceOrderRequest) => Promise<boolean>
  selectOrder: (orderId: number) => void
  addStock: (skuCode: string, quantity: number) => Promise<void>
  reset: (() => void) | null
  clearError: () => void
}

function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  return new ApiError(0, 'UNEXPECTED', error instanceof Error ? error.message : 'Something went wrong.')
}

/**
 * Holds everything the console shows and keeps it current. The same logic
 * drives both modes: events trigger a refresh, and the active order is also
 * polled, because a 202 Accepted only means "the saga has started".
 */
export function useSagaConsole(backend: SagaBackend): SagaConsole {
  const [orders, setOrders] = useState<Order[]>([])
  const [stock, setStock] = useState<Stock[]>([])
  const [payments, setPayments] = useState<Payment[]>([])
  const [events, setEvents] = useState<SagaEvent[]>([])
  const [activeOrderId, setActiveOrderId] = useState<number | null>(null)
  const [streamState, setStreamState] = useState<StreamState>('connecting')
  const [placing, setPlacing] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)
  const followUpTimers = useRef<ReturnType<typeof setTimeout>[]>([])

  const upsertOrder = useCallback((order: Order) => {
    setOrders((current) => {
      const others = current.filter((existing) => existing.id !== order.id)
      return [order, ...others].sort((a, b) => b.id - a.id)
    })
  }, [])

  const mergeEvents = useCallback((incoming: SagaEvent[]) => {
    if (incoming.length === 0) return
    setEvents((current) => {
      const seen = new Set(current.map(eventKey))
      const fresh = incoming.filter((event) => !seen.has(eventKey(event)))
      if (fresh.length === 0) return current
      return [...current, ...fresh].slice(-MAX_EVENTS)
    })
  }, [])

  const refreshAll = useCallback(async () => {
    try {
      const [nextOrders, nextStock, nextPayments] = await Promise.all([
        backend.listOrders(),
        backend.listStock(),
        backend.listPayments(),
      ])
      setOrders([...nextOrders].sort((a, b) => b.id - a.id))
      setStock([...nextStock].sort((a, b) => a.skuCode.localeCompare(b.skuCode)))
      setPayments([...nextPayments].reverse())
    } catch (caught) {
      setError(toApiError(caught))
    }
  }, [backend])

  /** Coalesces the burst of refreshes a saga produces into one round of requests. */
  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current)
    refreshTimer.current = setTimeout(() => {
      refreshTimer.current = null
      void refreshAll()
    }, 120)
  }, [refreshAll])

  const stopPolling = useCallback(() => {
    if (pollTimer.current) clearInterval(pollTimer.current)
    pollTimer.current = null
    followUpTimers.current.forEach((timer) => clearTimeout(timer))
    followUpTimers.current = []
  }, [])

  const startPolling = useCallback((orderId: number) => {
    stopPolling()
    const startedAt = Date.now()
    let lastStatus: string | null = null

    pollTimer.current = setInterval(async () => {
      try {
        const order = await backend.getOrder(orderId)
        upsertOrder(order)
        if (order.status !== lastStatus) {
          lastStatus = order.status
          scheduleRefresh()
        }
        if (isFinished(order.status) || Date.now() - startedAt > POLL_GIVE_UP_MS) {
          if (pollTimer.current) clearInterval(pollTimer.current)
          pollTimer.current = null
          // Compensation and commit happen after the order's final status is
          // set, so look again shortly afterwards to catch the stock moving.
          followUpTimers.current = [1200, 3000].map((delay) => setTimeout(scheduleRefresh, delay))
        }
      } catch (caught) {
        stopPolling()
        setError(toApiError(caught))
      }
    }, POLL_MS)
  }, [backend, scheduleRefresh, stopPolling, upsertOrder])

  // Connect to the backend: load state, then follow the event stream. The
  // console remounts this hook for each backend, so state always starts empty.
  useEffect(() => {
    // Loading data from the backend; state is set when the requests resolve.
    // oxlint-disable-next-line react/set-state-in-effect
    void refreshAll()
    void backend.recentEvents().then(mergeEvents)

    const stopEvents = backend.onEvent((event) => {
      mergeEvents([event])
      scheduleRefresh()
    })
    const stopState = backend.onStreamState(setStreamState)
    const stopReset = backend instanceof SimulatedBackend
      ? backend.onReset(() => {
          stopPolling()
          setEvents([])
          setActiveOrderId(null)
          void refreshAll()
        })
      : () => undefined

    return () => {
      stopEvents()
      stopState()
      stopReset()
      stopPolling()
      if (refreshTimer.current) clearTimeout(refreshTimer.current)
    }
  }, [backend, mergeEvents, refreshAll, scheduleRefresh, stopPolling])

  // When the live feed (re)connects, catch up on anything missed for the active order.
  useEffect(() => {
    if (streamState !== 'open' || activeOrderId === null) return
    void backend.recentEvents(activeOrderId).then(mergeEvents)
  }, [backend, streamState, activeOrderId, mergeEvents])

  const placeOrder = useCallback(async (request: PlaceOrderRequest) => {
    setPlacing(true)
    setError(null)
    try {
      const order = await backend.placeOrder(request)
      upsertOrder(order)
      setActiveOrderId(order.id)
      startPolling(order.id)
      scheduleRefresh()
      return true
    } catch (caught) {
      setError(toApiError(caught))
      return false
    } finally {
      setPlacing(false)
    }
  }, [backend, scheduleRefresh, startPolling, upsertOrder])

  const selectOrder = useCallback((orderId: number) => {
    setActiveOrderId(orderId)
    void backend.recentEvents(orderId).then(mergeEvents)
    void backend.getOrder(orderId).then((order) => {
      upsertOrder(order)
      if (!isFinished(order.status)) startPolling(order.id)
    }).catch((caught) => setError(toApiError(caught)))
  }, [backend, mergeEvents, startPolling, upsertOrder])

  const addStock = useCallback(async (skuCode: string, quantity: number) => {
    try {
      await backend.addStock(skuCode, quantity)
      scheduleRefresh()
    } catch (caught) {
      setError(toApiError(caught))
    }
  }, [backend, scheduleRefresh])

  const activeOrder = useMemo(
    () => orders.find((order) => order.id === activeOrderId) ?? null,
    [orders, activeOrderId],
  )

  const activeEvents = useMemo(() => {
    if (!activeOrder) return []
    const earliest = new Date(activeOrder.createdAt).getTime() - STALE_EVENT_MS
    return events
      .filter((event) => event.orderId === activeOrder.id && new Date(event.occurredAt).getTime() >= earliest)
      .sort(chronological)
  }, [events, activeOrder])

  const reset = useMemo(
    () => (backend instanceof SimulatedBackend ? () => backend.reset() : null),
    [backend],
  )

  const clearError = useCallback(() => setError(null), [])

  return {
    orders,
    stock,
    payments,
    activeOrder,
    activeEvents,
    streamState,
    placing,
    error,
    placeOrder,
    selectOrder,
    addStock,
    reset,
    clearError,
  }
}
