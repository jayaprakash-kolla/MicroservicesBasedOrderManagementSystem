import type { PlaceOrderRequest, Stock } from './types'

/** The stock inventory-service seeds at startup (StockInitializer.java). */
export const SEED_STOCK: readonly Stock[] = [
  { skuCode: 'LAPTOP-001', availableQuantity: 100, reservedQuantity: 0 },
  { skuCode: 'PHONE-002', availableQuantity: 50, reservedQuantity: 0 },
  { skuCode: 'RARE-003', availableQuantity: 1, reservedQuantity: 0 },
]

/**
 * Default unit prices the order form suggests. The backend has no product
 * catalog: order-service accepts whatever price the client sends.
 */
export const SUGGESTED_PRICES: Readonly<Record<string, number>> = {
  'LAPTOP-001': 500,
  'PHONE-002': 300,
  'RARE-003': 100,
}

/** payment.decline-above-amount in payment-service/application.yml. */
export const DECLINE_ABOVE_AMOUNT = 100000

export type ScenarioId = 'happy' | 'out-of-stock' | 'declined'

export interface Scenario {
  id: ScenarioId
  name: string
  summary: string
  outcome: string
  request: PlaceOrderRequest
}

/** The three paths through the saga, using the same orders as the README. */
export const SCENARIOS: readonly Scenario[] = [
  {
    id: 'happy',
    name: 'Normal order',
    summary: '2 × LAPTOP-001 at 500',
    outcome: 'Stock is held, the card is charged, and the order is confirmed.',
    request: {
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 2, price: 500 }],
    },
  },
  {
    id: 'out-of-stock',
    name: 'Not enough stock',
    summary: '5 × RARE-003, only 1 in stock',
    outcome: 'Inventory refuses before anything is held, so there is nothing to undo.',
    request: {
      customerId: 'cust-1',
      items: [{ skuCode: 'RARE-003', quantity: 5, price: 100 }],
    },
  },
  {
    id: 'declined',
    name: 'Card declined',
    summary: '3 × LAPTOP-001 at 50,000',
    outcome: 'Stock is held, then the card is declined, so the held stock is given back.',
    request: {
      customerId: 'cust-1',
      items: [{ skuCode: 'LAPTOP-001', quantity: 3, price: 50000 }],
    },
  },
]
