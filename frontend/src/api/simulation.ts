import type { BackendMode, SagaBackend, StreamState } from './backend'
import { DECLINE_ABOVE_AMOUNT, SEED_STOCK } from './catalog'
import { TOPIC, TOPIC_INFO, type TopicName } from '../saga/topics'
import {
  ApiError,
  type Order,
  type OrderStatus,
  type Payment,
  type PlaceOrderRequest,
  type SagaEvent,
  type ServiceName,
  type Stock,
} from './types'

/*
 * A faithful, in-browser model of order, inventory and payment service.
 *
 * Every rule here is a port of a specific Java method, named in the comments,
 * so the simulation behaves like the real system rather than like a script:
 *
 *   - services only talk through topics; each consumer group gets its own copy
 *   - reservation is all-or-nothing (InventoryService.reserveForOrder)
 *   - payment declines strictly above the threshold (SimulatedPaymentGateway)
 *   - every consumer is idempotent, exactly as in the Java services
 *
 * Delivery is delayed by `stepDelayMs` so a person can follow each hop. The
 * real system finishes the whole saga in well under a second.
 */

type Payload = Record<string, unknown>
type Handler = (payload: Payload) => void

interface Subscription {
  topic: TopicName
  group: ServiceName
  handler: Handler
}

interface OrderRecord {
  id: number
  orderNumber: string
  customerId: string
  status: OrderStatus
  totalCents: number
  /** BigDecimal scale of the total, so amounts print the way Java prints them. */
  scale: number
  failureReason: string | null
  createdAt: string
  updatedAt: string
  items: { skuCode: string; quantity: number; priceCents: number }[]
}

interface StockRecord {
  skuCode: string
  available: number
  reserved: number
}

type HoldStatus = 'RESERVED' | 'COMMITTED' | 'RELEASED'

interface Hold {
  id: number
  orderId: number
  skuCode: string
  quantity: number
  status: HoldStatus
}

interface PaymentRecord {
  paymentReference: string
  orderId: number
  customerId: string
  amountCents: number
  status: 'COMPLETED' | 'DECLINED'
  failureReason: string | null
  createdAt: string
}

export interface SimulationOptions {
  /** Delay between a message being published and a consumer handling it. */
  stepDelayMs?: number
  declineAboveAmount?: number
  seed?: readonly Stock[]
}

const EVENT_BUFFER = 500

function toCents(amount: number): number {
  return Math.round(amount * 100)
}

/** Decimal places in a JSON number as Jackson would read it into a BigDecimal (capped at 2). */
function scaleOf(value: number): number {
  const text = String(value)
  const dot = text.indexOf('.')
  return dot === -1 ? 0 : Math.min(2, text.length - dot - 1)
}

/** What BigDecimal.toString() would print for this amount at this scale. */
function javaDecimal(cents: number, scale: number): string {
  return (cents / 100).toFixed(scale)
}

function randomReference(prefix: string): string {
  const hex = Array.from({ length: 8 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
  return `${prefix}-${hex.toUpperCase()}`
}

export class SimulatedBackend implements SagaBackend {
  readonly mode: BackendMode = 'simulation'

  readonly stepDelayMs: number
  readonly declineAboveAmount: number

  private readonly seed: readonly Stock[]
  private readonly subscriptions: Subscription[] = []
  private readonly eventListeners = new Set<(event: SagaEvent) => void>()
  private readonly resetListeners = new Set<() => void>()
  private readonly timers = new Set<ReturnType<typeof setTimeout>>()

  private orders = new Map<number, OrderRecord>()
  private stock = new Map<string, StockRecord>()
  private holds: Hold[] = []
  private payments: PaymentRecord[] = []
  private events: SagaEvent[] = []
  private offsets = new Map<string, number>()
  private nextOrderId = 1
  private nextHoldId = 1
  private sequence = 0

  constructor(options: SimulationOptions = {}) {
    this.stepDelayMs = options.stepDelayMs ?? 900
    this.declineAboveAmount = options.declineAboveAmount ?? DECLINE_ABOVE_AMOUNT
    this.seed = options.seed ?? SEED_STOCK
    this.seedStock()

    // The @KafkaListener methods of the three services, one line each.
    this.listen(TOPIC.ORDER_CREATED, 'inventory-service', (p) => this.inventoryReserveForOrder(p))
    this.listen(TOPIC.ORDER_CANCELLED, 'inventory-service', (p) => this.inventoryReleaseForOrder(p))
    this.listen(TOPIC.PAYMENT_COMPLETED, 'inventory-service', (p) => this.inventoryCommitForOrder(p))

    this.listen(TOPIC.INVENTORY_RESERVED, 'order-service', (p) => this.orderMarkInventoryReserved(p))
    this.listen(TOPIC.INVENTORY_FAILED, 'order-service', (p) => this.orderCancelForInventoryFailure(p))
    this.listen(TOPIC.PAYMENT_COMPLETED, 'order-service', (p) => this.orderConfirm(p))
    this.listen(TOPIC.PAYMENT_FAILED, 'order-service', (p) => this.orderCancelForPaymentFailure(p))

    this.listen(TOPIC.INVENTORY_RESERVED, 'payment-service', (p) => this.paymentProcess(p))
  }

  // ---------------------------------------------------------------- public API

  async placeOrder(request: PlaceOrderRequest): Promise<Order> {
    const fields = validateOrder(request)
    if (Object.keys(fields).length > 0) {
      throw new ApiError(400, 'VALIDATION_FAILED', 'The order is not valid.', fields)
    }

    // OrderService.placeOrder: save as PENDING, then publish. Save before publish.
    const now = new Date().toISOString()
    const items = request.items.map((line) => ({
      skuCode: line.skuCode.trim(),
      quantity: line.quantity,
      priceCents: toCents(line.price),
    }))
    const order: OrderRecord = {
      id: this.nextOrderId++,
      orderNumber: randomReference('ORD'),
      customerId: request.customerId.trim(),
      status: 'PENDING',
      totalCents: items.reduce((sum, line) => sum + line.priceCents * line.quantity, 0),
      scale: Math.max(0, ...request.items.map((line) => scaleOf(line.price))),
      failureReason: null,
      createdAt: now,
      updatedAt: now,
      items,
    }
    this.orders.set(order.id, order)

    this.publish(TOPIC.ORDER_CREATED, {
      orderId: order.id,
      orderNumber: order.orderNumber,
      customerId: order.customerId,
      items: items.map((line) => ({ skuCode: line.skuCode, quantity: line.quantity, price: line.priceCents / 100 })),
      totalAmount: order.totalCents / 100,
    })

    return toOrder(order)
  }

  async getOrder(id: number): Promise<Order> {
    const order = this.orders.get(id)
    if (!order) throw new ApiError(404, 'ORDER_NOT_FOUND', `No order with id ${id}`)
    return toOrder(order)
  }

  async listOrders(): Promise<Order[]> {
    return [...this.orders.values()].map(toOrder)
  }

  async listStock(): Promise<Stock[]> {
    return [...this.stock.values()].map((item) => ({
      skuCode: item.skuCode,
      availableQuantity: item.available,
      reservedQuantity: item.reserved,
    }))
  }

  async addStock(skuCode: string, quantity: number): Promise<Stock> {
    const fields: Record<string, string> = {}
    if (!skuCode.trim()) fields.skuCode = 'skuCode is required'
    if (!(quantity >= 1)) fields.quantity = 'quantity must be at least 1'
    if (Object.keys(fields).length > 0) {
      throw new ApiError(400, 'VALIDATION_FAILED', 'The restock request is not valid.', fields)
    }
    // InventoryService.addStock: creates the SKU if it is new.
    const key = skuCode.trim()
    const item = this.stock.get(key) ?? { skuCode: key, available: 0, reserved: 0 }
    item.available += quantity
    this.stock.set(key, item)
    return { skuCode: item.skuCode, availableQuantity: item.available, reservedQuantity: item.reserved }
  }

  async listPayments(): Promise<Payment[]> {
    return this.payments.map((payment) => ({
      paymentReference: payment.paymentReference,
      orderId: payment.orderId,
      customerId: payment.customerId,
      amount: payment.amountCents / 100,
      status: payment.status,
      failureReason: payment.failureReason,
      createdAt: payment.createdAt,
    }))
  }

  async recentEvents(orderId?: number): Promise<SagaEvent[]> {
    return orderId === undefined ? [...this.events] : this.events.filter((event) => event.orderId === orderId)
  }

  onEvent(listener: (event: SagaEvent) => void): () => void {
    this.eventListeners.add(listener)
    return () => this.eventListeners.delete(listener)
  }

  onStreamState(listener: (state: StreamState) => void): () => void {
    listener('open')
    return () => undefined
  }

  /** Called after reset() so the page can clear what it is showing. */
  onReset(listener: () => void): () => void {
    this.resetListeners.add(listener)
    return () => this.resetListeners.delete(listener)
  }

  /** Puts everything back to the seeded state, like restarting the services. */
  reset(): void {
    this.cancelTimers()
    this.orders = new Map()
    this.holds = []
    this.payments = []
    this.events = []
    this.offsets = new Map()
    this.nextOrderId = 1
    this.nextHoldId = 1
    this.sequence = 0
    this.seedStock()
    this.resetListeners.forEach((listener) => listener())
  }

  close(): void {
    this.cancelTimers()
    this.eventListeners.clear()
    this.resetListeners.clear()
  }

  // ------------------------------------------------------------- the "Kafka"

  private listen(topic: TopicName, group: ServiceName, handler: Handler): void {
    this.subscriptions.push({ topic, group, handler })
  }

  /** Appends to the topic's log, notifies observers, and schedules delivery to each consumer group. */
  private publish(topic: TopicName, payload: Payload): void {
    const offset = this.offsets.get(topic) ?? 0
    this.offsets.set(topic, offset + 1)

    const event: SagaEvent = {
      sequence: ++this.sequence,
      topic,
      eventType: TOPIC_INFO[topic].eventType,
      publishedBy: TOPIC_INFO[topic].publishedBy,
      orderId: typeof payload.orderId === 'number' ? payload.orderId : null,
      deadLetter: false,
      payload,
      occurredAt: new Date().toISOString(),
      partition: 0,
      offset,
    }
    this.events.push(event)
    if (this.events.length > EVENT_BUFFER) this.events.shift()
    this.eventListeners.forEach((listener) => listener(event))

    for (const subscription of this.subscriptions) {
      if (subscription.topic === topic) {
        this.schedule(() => subscription.handler(payload))
      }
    }
  }

  private schedule(task: () => void): void {
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      task()
    }, this.stepDelayMs)
    this.timers.add(timer)
  }

  private cancelTimers(): void {
    this.timers.forEach((timer) => clearTimeout(timer))
    this.timers.clear()
  }

  private seedStock(): void {
    this.stock = new Map(
      this.seed.map((item) => [
        item.skuCode,
        { skuCode: item.skuCode, available: item.availableQuantity, reserved: item.reservedQuantity },
      ]),
    )
  }

  // --------------------------------------------------------- inventory-service

  /** InventoryService.reserveForOrder */
  private inventoryReserveForOrder(payload: Payload): void {
    const orderId = payload.orderId as number
    const lines = payload.items as { skuCode: string; quantity: number }[]

    // Idempotency: a redelivered order event must not reserve twice.
    if (this.holds.some((hold) => hold.orderId === orderId)) return

    // Phase 1: check everything before changing anything.
    for (const line of lines) {
      const item = this.stock.get(line.skuCode)
      const problem = !item
        ? `Unknown SKU: ${line.skuCode}`
        : item.available < line.quantity
          ? `Insufficient stock for ${line.skuCode}: requested ${line.quantity}, available ${item.available}`
          : null
      if (problem) {
        this.publish(TOPIC.INVENTORY_FAILED, { orderId, reason: problem })
        return
      }
    }

    // Phase 2: nothing can fail now, so apply the changes.
    const created: Hold[] = []
    for (const line of lines) {
      const item = this.stock.get(line.skuCode)!
      item.available -= line.quantity
      item.reserved += line.quantity
      created.push({ id: this.nextHoldId++, orderId, skuCode: line.skuCode, quantity: line.quantity, status: 'RESERVED' })
    }
    this.holds.push(...created)

    this.publish(TOPIC.INVENTORY_RESERVED, {
      orderId,
      reservationId: created[0].id,
      customerId: payload.customerId,
      totalAmount: payload.totalAmount,
    })
  }

  /** InventoryService.releaseForOrder: the compensating transaction. */
  private inventoryReleaseForOrder(payload: Payload): void {
    for (const hold of this.activeHolds(payload.orderId as number)) {
      const item = this.stock.get(hold.skuCode)!
      item.reserved -= hold.quantity
      item.available += hold.quantity
      hold.status = 'RELEASED'
    }
  }

  /** InventoryService.commitForOrder: held units become a permanent reduction. */
  private inventoryCommitForOrder(payload: Payload): void {
    for (const hold of this.activeHolds(payload.orderId as number)) {
      this.stock.get(hold.skuCode)!.reserved -= hold.quantity
      hold.status = 'COMMITTED'
    }
  }

  /** Filtering on RESERVED is what makes release and commit safe to run twice. */
  private activeHolds(orderId: number): Hold[] {
    return this.holds.filter((hold) => hold.orderId === orderId && hold.status === 'RESERVED')
  }

  // ------------------------------------------------------------- order-service

  /** OrderService.markInventoryReserved */
  private orderMarkInventoryReserved(payload: Payload): void {
    const order = this.orders.get(payload.orderId as number)
    if (!order || order.status !== 'PENDING') return
    this.changeStatus(order, 'INVENTORY_RESERVED')
  }

  /** OrderService.cancelForInventoryFailure: nothing was held, so nothing to compensate. */
  private orderCancelForInventoryFailure(payload: Payload): void {
    const order = this.orders.get(payload.orderId as number)
    if (!order || isTerminal(order.status)) return
    order.failureReason = String(payload.reason)
    this.changeStatus(order, 'CANCELLED')
  }

  /** OrderService.confirmOrder */
  private orderConfirm(payload: Payload): void {
    const order = this.orders.get(payload.orderId as number)
    if (!order || isTerminal(order.status)) return
    this.changeStatus(order, 'CONFIRMED')
  }

  /** OrderService.cancelForPaymentFailure: cancel, then publish the compensation. */
  private orderCancelForPaymentFailure(payload: Payload): void {
    const order = this.orders.get(payload.orderId as number)
    if (!order || isTerminal(order.status)) return
    order.failureReason = String(payload.reason)
    this.changeStatus(order, 'CANCELLED')
    this.publish(TOPIC.ORDER_CANCELLED, { orderId: order.id, reason: payload.reason })
  }

  private changeStatus(order: OrderRecord, status: OrderStatus): void {
    order.status = status
    order.updatedAt = new Date().toISOString()
  }

  // ----------------------------------------------------------- payment-service

  /** PaymentService.processPayment with SimulatedPaymentGateway.charge */
  private paymentProcess(payload: Payload): void {
    const orderId = payload.orderId as number

    // Idempotency: the Java service has a unique constraint on order_id.
    if (this.payments.some((payment) => payment.orderId === orderId)) return

    const order = this.orders.get(orderId)
    const amountCents = toCents(payload.totalAmount as number)
    const amountText = javaDecimal(amountCents, order?.scale ?? 2)
    const declined = amountCents > toCents(this.declineAboveAmount)
    const reference = randomReference('PAY')
    const customerId = String(payload.customerId)

    if (declined) {
      const reason = `Card declined: amount ${amountText} exceeds limit ${this.declineAboveAmount}`
      this.payments.push({
        paymentReference: reference, orderId, customerId, amountCents,
        status: 'DECLINED', failureReason: reason, createdAt: new Date().toISOString(),
      })
      this.publish(TOPIC.PAYMENT_FAILED, { orderId, reason })
      return
    }

    this.payments.push({
      paymentReference: reference, orderId, customerId, amountCents,
      status: 'COMPLETED', failureReason: null, createdAt: new Date().toISOString(),
    })
    this.publish(TOPIC.PAYMENT_COMPLETED, { orderId, paymentId: reference, amount: amountCents / 100 })
  }
}

function isTerminal(status: OrderStatus): boolean {
  return status === 'CONFIRMED' || status === 'CANCELLED'
}

function toOrder(order: OrderRecord): Order {
  return {
    id: order.id,
    orderNumber: order.orderNumber,
    customerId: order.customerId,
    status: order.status,
    totalAmount: order.totalCents / 100,
    failureReason: order.failureReason,
    createdAt: order.createdAt,
    updatedAt: order.updatedAt,
    items: order.items.map((line) => ({ skuCode: line.skuCode, quantity: line.quantity, price: line.priceCents / 100 })),
  }
}

/** The Bean Validation rules on PlaceOrderRequest and OrderLineItemRequest. */
export function validateOrder(request: PlaceOrderRequest): Record<string, string> {
  const fields: Record<string, string> = {}
  if (!request.customerId || !request.customerId.trim()) fields.customerId = 'customerId is required'
  if (!request.items || request.items.length === 0) fields.items = 'at least one item is required'
  request.items?.forEach((line, index) => {
    if (!line.skuCode || !line.skuCode.trim()) fields[`items[${index}].skuCode`] = 'skuCode is required'
    if (!(line.quantity >= 1)) fields[`items[${index}].quantity`] = 'quantity must be at least 1'
    if (line.price === null || line.price === undefined || Number.isNaN(line.price)) {
      fields[`items[${index}].price`] = 'price is required'
    } else if (line.price < 0.01) {
      fields[`items[${index}].price`] = 'price must be greater than zero'
    }
  })
  return fields
}
