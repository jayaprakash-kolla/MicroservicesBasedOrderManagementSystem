import type { Order } from '../api/types'
import { formatAmount } from '../format'

interface OrderHistoryProps {
  orders: Order[]
  activeOrderId: number | null
  onSelect: (orderId: number) => void
}

export function OrderHistory({ orders, activeOrderId, onSelect }: OrderHistoryProps) {
  if (orders.length === 0) {
    return <p className="empty">Orders you place appear here. Select one to replay its events.</p>
  }

  return (
    <ul className="ledger">
      {orders.slice(0, 8).map((order) => (
        <li key={order.id}>
          <button
            type="button"
            className="ledger__row ledger__row--button"
            data-status={order.status}
            aria-current={order.id === activeOrderId ? 'true' : undefined}
            onClick={() => onSelect(order.id)}
          >
            <code className="ledger__ref">{order.orderNumber}</code>
            <span className="ledger__amount">{formatAmount(order.totalAmount)}</span>
            <code className="ledger__status">{order.status}</code>
            <span className="ledger__detail">
              Order {order.id}: {order.items.map((line) => `${line.quantity} × ${line.skuCode}`).join(', ')}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}
