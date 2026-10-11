/*
 * The shapes the backend returns. Each one mirrors a Java DTO so the UI never
 * needs to know whether it is talking to the in-browser simulation or to the
 * real services:
 *
 *   Order        <- order-service   OrderResponse
 *   Stock        <- inventory-service StockResponse
 *   Payment      <- payment-service PaymentResponse
 *   SagaEvent    <- saga-monitor    SagaEvent
 */

export type OrderStatus = 'PENDING' | 'INVENTORY_RESERVED' | 'CONFIRMED' | 'CANCELLED'

export interface OrderLineItem {
  skuCode: string
  quantity: number
  price: number
}

export interface Order {
  id: number
  orderNumber: string
  customerId: string
  status: OrderStatus
  totalAmount: number
  failureReason: string | null
  createdAt: string
  updatedAt: string
  items: OrderLineItem[]
}

export interface PlaceOrderRequest {
  customerId: string
  items: OrderLineItem[]
}

export interface Stock {
  skuCode: string
  availableQuantity: number
  reservedQuantity: number
}

export type PaymentStatus = 'COMPLETED' | 'DECLINED'

export interface Payment {
  paymentReference: string
  orderId: number
  customerId: string
  amount: number
  status: PaymentStatus
  failureReason: string | null
  createdAt: string
}

export type ServiceName = 'order-service' | 'inventory-service' | 'payment-service'

export interface SagaEvent {
  sequence: number
  topic: string
  eventType: string
  publishedBy: string
  orderId: number | null
  deadLetter: boolean
  payload: Record<string, unknown> | string
  occurredAt: string
  partition: number
  offset: number
}

export function isFinished(status: OrderStatus): boolean {
  return status === 'CONFIRMED' || status === 'CANCELLED'
}

/** Kafka coordinates are unique per record, so they make a safe de-duplication key. */
export function eventKey(event: SagaEvent): string {
  return `${event.topic}:${event.partition}:${event.offset}`
}

/**
 * An error in the same shape order-service's GlobalExceptionHandler produces:
 * an error code, an optional message, and optional per-field messages.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fields: Record<string, string>

  constructor(status: number, code: string, message: string, fields: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fields = fields
  }
}
