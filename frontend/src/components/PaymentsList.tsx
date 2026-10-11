import type { Payment } from '../api/types'
import { formatAmount } from '../format'

export function PaymentsList({ payments }: { payments: Payment[] }) {
  if (payments.length === 0) {
    return <p className="empty">No charges yet. Payment-service only charges once stock is held.</p>
  }

  return (
    <ul className="ledger">
      {payments.slice(0, 8).map((payment) => (
        <li key={payment.paymentReference} className="ledger__row" data-status={payment.status}>
          <code className="ledger__ref">{payment.paymentReference}</code>
          <span className="ledger__amount">{formatAmount(payment.amount)}</span>
          <span className="ledger__status">{payment.status === 'COMPLETED' ? 'Completed' : 'Declined'}</span>
          <span className="ledger__detail">Order {payment.orderId}{payment.failureReason ? `. ${payment.failureReason}` : ''}</span>
        </li>
      ))}
    </ul>
  )
}
