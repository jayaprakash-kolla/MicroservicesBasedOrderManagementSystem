import type { ServiceName } from '../api/types'

/** The six saga topics (common-events/Topics.java). */
export const TOPIC = {
  ORDER_CREATED: 'order-created',
  ORDER_CANCELLED: 'order-cancelled',
  INVENTORY_RESERVED: 'inventory-reserved',
  INVENTORY_FAILED: 'inventory-failed',
  PAYMENT_COMPLETED: 'payment-completed',
  PAYMENT_FAILED: 'payment-failed',
} as const

export type TopicName = (typeof TOPIC)[keyof typeof TOPIC]

export const DLT_SUFFIX = '.DLT'

export interface TopicInfo {
  eventType: string
  publishedBy: ServiceName
  /** Every consumer group that reads the topic, in the order the listeners are declared. */
  consumedBy: ServiceName[]
}

/**
 * Who publishes and who consumes each topic. This table is the choreography:
 * there is no coordinator, only these subscriptions.
 */
export const TOPIC_INFO: Record<TopicName, TopicInfo> = {
  'order-created': {
    eventType: 'OrderCreatedEvent',
    publishedBy: 'order-service',
    consumedBy: ['inventory-service'],
  },
  'inventory-reserved': {
    eventType: 'InventoryReservedEvent',
    publishedBy: 'inventory-service',
    consumedBy: ['order-service', 'payment-service'],
  },
  'inventory-failed': {
    eventType: 'InventoryFailedEvent',
    publishedBy: 'inventory-service',
    consumedBy: ['order-service'],
  },
  'payment-completed': {
    eventType: 'PaymentCompletedEvent',
    publishedBy: 'payment-service',
    consumedBy: ['order-service', 'inventory-service'],
  },
  'payment-failed': {
    eventType: 'PaymentFailedEvent',
    publishedBy: 'payment-service',
    consumedBy: ['order-service'],
  },
  'order-cancelled': {
    eventType: 'OrderCancelledEvent',
    publishedBy: 'order-service',
    consumedBy: ['inventory-service'],
  },
}

/** Service order across the page, left to right. */
export const SERVICES: readonly ServiceName[] = ['order-service', 'inventory-service', 'payment-service']

export const SERVICE_PORT: Record<ServiceName, number> = {
  'order-service': 18081,
  'inventory-service': 18082,
  'payment-service': 18083,
}

export function baseTopic(topic: string): string {
  return topic.endsWith(DLT_SUFFIX) ? topic.slice(0, -DLT_SUFFIX.length) : topic
}

export function topicInfo(topic: string): TopicInfo | undefined {
  return TOPIC_INFO[baseTopic(topic) as TopicName]
}
