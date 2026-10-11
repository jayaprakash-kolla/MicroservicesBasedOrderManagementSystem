import type { Stock } from '../api/types'
import { formatCount } from '../format'

interface StockTableProps {
  stock: Stock[]
  onRestock: (skuCode: string) => void
  /** Hide the restock buttons, e.g. in the hero. */
  readOnly?: boolean
}

/**
 * Available and held units per SKU. "Held" is inventory-service's
 * reservedQuantity: units taken out of sale for orders whose payment has not
 * finished. Watching it rise and fall is the clearest view of the saga.
 */
export function StockTable({ stock, onRestock, readOnly = false }: StockTableProps) {
  if (stock.length === 0) {
    return <p className="empty">No stock rows yet. Inventory-service seeds three SKUs when it starts.</p>
  }

  return (
    <ul className="stock">
      {stock.map((item) => {
        const capacity = Math.max(1, item.availableQuantity + item.reservedQuantity)
        return (
          <li key={item.skuCode} className="stock__row">
            <div className="stock__top">
              <code className="stock__sku">{item.skuCode}</code>
              <span className="stock__numbers">
                {formatCount(item.availableQuantity)} available
                {item.reservedQuantity > 0 && <strong className="stock__held">, {formatCount(item.reservedQuantity)} held</strong>}
              </span>
              {!readOnly && (
                <button type="button" className="link-button" onClick={() => onRestock(item.skuCode)}>
                  Add 10
                </button>
              )}
            </div>
            <div
              className="stock__bar"
              role="img"
              aria-label={`${item.availableQuantity} available, ${item.reservedQuantity} held`}
            >
              <span className="stock__available" style={{ width: `${(item.availableQuantity / capacity) * 100}%` }} />
              <span className="stock__reserved" style={{ width: `${(item.reservedQuantity / capacity) * 100}%` }} />
            </div>
          </li>
        )
      })}
    </ul>
  )
}
