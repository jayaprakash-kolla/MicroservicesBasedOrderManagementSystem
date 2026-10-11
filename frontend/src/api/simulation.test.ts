import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SimulatedBackend } from './simulation'
import { ApiError, type SagaEvent } from './types'

/*
 * The simulation is only worth showing if it behaves like the Java services.
 * These tests pin it to the same outcomes the backend's own tests prove.
 */

const STEP = 100

function newBackend() {
  const backend = new SimulatedBackend({ stepDelayMs: STEP })
  const events: SagaEvent[] = []
  backend.onEvent((event) => events.push(event))
  return { backend, events }
}

async function settle() {
  // Long enough for the longest path (four hops) to finish.
  await vi.advanceTimersByTimeAsync(STEP * 10)
}

describe('SimulatedBackend', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('accepts an order as PENDING before any consumer has run, like the 202 response', async () => {
    const { backend } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 2, price: 500 }],
    })

    expect(order.status).toBe('PENDING')
    expect(order.totalAmount).toBe(1000)
    expect(order.orderNumber).toMatch(/^ORD-[0-9A-F]{8}$/)
  })

  it('confirms a normal order and commits the held stock', async () => {
    const { backend, events } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 2, price: 500 }],
    })
    await settle()

    expect((await backend.getOrder(order.id)).status).toBe('CONFIRMED')
    expect(events.map((event) => event.topic)).toEqual(['order-created', 'inventory-reserved', 'payment-completed'])

    const laptop = (await backend.listStock()).find((item) => item.skuCode === 'LAPTOP-001')!
    expect(laptop.availableQuantity).toBe(98)
    expect(laptop.reservedQuantity).toBe(0)

    const [payment] = await backend.listPayments()
    expect(payment.status).toBe('COMPLETED')
    expect(payment.amount).toBe(1000)
  })

  it('cancels without compensation when stock is short, and touches no stock', async () => {
    const { backend, events } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'RARE-003', quantity: 5, price: 100 }],
    })
    await settle()

    const result = await backend.getOrder(order.id)
    expect(result.status).toBe('CANCELLED')
    expect(result.failureReason).toBe('Insufficient stock for RARE-003: requested 5, available 1')
    expect(events.map((event) => event.topic)).toEqual(['order-created', 'inventory-failed'])
    expect(await backend.listPayments()).toHaveLength(0)

    const rare = (await backend.listStock()).find((item) => item.skuCode === 'RARE-003')!
    expect(rare).toMatchObject({ availableQuantity: 1, reservedQuantity: 0 })
  })

  it('reserves nothing at all when only some lines can be satisfied', async () => {
    const { backend } = newBackend()

    await backend.placeOrder({
      customerId: 'cust-1',
      items: [
        { skuCode: 'LAPTOP-001', quantity: 2, price: 500 },
        { skuCode: 'RARE-003', quantity: 5, price: 100 },
      ],
    })
    await settle()

    const laptop = (await backend.listStock()).find((item) => item.skuCode === 'LAPTOP-001')!
    expect(laptop).toMatchObject({ availableQuantity: 100, reservedQuantity: 0 })
  })

  it('declines a large charge and gives the held stock back', async () => {
    const { backend, events } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 3, price: 50000 }],
    })

    // Halfway through: stock is held while the payment is being attempted.
    await vi.advanceTimersByTimeAsync(STEP)
    const held = (await backend.listStock()).find((item) => item.skuCode === 'LAPTOP-001')!
    expect(held).toMatchObject({ availableQuantity: 97, reservedQuantity: 3 })

    await settle()

    const result = await backend.getOrder(order.id)
    expect(result.status).toBe('CANCELLED')
    expect(result.failureReason).toBe('Card declined: amount 150000 exceeds limit 100000')
    expect(events.map((event) => event.topic)).toEqual([
      'order-created', 'inventory-reserved', 'payment-failed', 'order-cancelled',
    ])

    const released = (await backend.listStock()).find((item) => item.skuCode === 'LAPTOP-001')!
    expect(released).toMatchObject({ availableQuantity: 100, reservedQuantity: 0 })

    const [payment] = await backend.listPayments()
    expect(payment.status).toBe('DECLINED')
  })

  it('treats the threshold as strictly greater-than, like SimulatedPaymentGateway', async () => {
    const { backend } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 2, price: 50000 }],
    })
    await settle()

    expect((await backend.getOrder(order.id)).status).toBe('CONFIRMED')
  })

  it('names an unknown SKU in the failure reason', async () => {
    const { backend } = newBackend()

    const order = await backend.placeOrder({
      customerId: 'cust-1',
      items: [{ skuCode: 'GHOST-999', quantity: 1, price: 10 }],
    })
    await settle()

    expect((await backend.getOrder(order.id)).failureReason).toBe('Unknown SKU: GHOST-999')
  })

  it('rejects an invalid order with per-field messages, like GlobalExceptionHandler', async () => {
    const { backend } = newBackend()

    const attempt = backend.placeOrder({
      customerId: ' ',
      items: [{ skuCode: 'LAPTOP-001', quantity: 0, price: 0 }],
    })

    await expect(attempt).rejects.toBeInstanceOf(ApiError)
    await attempt.catch((error: ApiError) => {
      expect(error.status).toBe(400)
      expect(error.fields).toEqual({
        customerId: 'customerId is required',
        'items[0].quantity': 'quantity must be at least 1',
        'items[0].price': 'price must be greater than zero',
      })
    })
  })

  it('returns 404 for an order that does not exist', async () => {
    const { backend } = newBackend()

    await expect(backend.getOrder(99)).rejects.toMatchObject({ status: 404, code: 'ORDER_NOT_FOUND' })
  })

  it('restores the seeded state on reset', async () => {
    const { backend } = newBackend()
    await backend.placeOrder({ customerId: 'cust-1', items: [{ skuCode: 'LAPTOP-001', quantity: 2, price: 500 }] })
    await settle()

    backend.reset()

    expect(await backend.listOrders()).toHaveLength(0)
    expect(await backend.listPayments()).toHaveLength(0)
    const laptop = (await backend.listStock()).find((item) => item.skuCode === 'LAPTOP-001')!
    expect(laptop.availableQuantity).toBe(100)
  })

  it('attributes each event to the service that publishes it', async () => {
    const { backend, events } = newBackend()
    await backend.placeOrder({ customerId: 'cust-1', items: [{ skuCode: 'LAPTOP-001', quantity: 3, price: 50000 }] })
    await settle()

    expect(events.map((event) => event.publishedBy)).toEqual([
      'order-service', 'inventory-service', 'payment-service', 'order-service',
    ])
    expect(events.every((event) => event.orderId === 1)).toBe(true)
  })
})
