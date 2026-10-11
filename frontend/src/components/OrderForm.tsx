import { useId, useState, type FormEvent } from 'react'
import { SUGGESTED_PRICES } from '../api/catalog'
import type { PlaceOrderRequest } from '../api/types'
import { formatAmount } from '../format'

interface Line {
  key: number
  skuCode: string
  quantity: string
  price: string
}

interface OrderFormProps {
  skus: string[]
  disabled: boolean
  /** Per-field messages from the last rejected request, keyed like the backend: items[0].price */
  serverFields: Record<string, string>
  onSubmit: (request: PlaceOrderRequest) => void
}

let nextKey = 1

function newLine(skuCode = 'LAPTOP-001'): Line {
  return { key: nextKey++, skuCode, quantity: '1', price: String(SUGGESTED_PRICES[skuCode] ?? 100) }
}

/**
 * Builds any order the API accepts, including ones it will refuse, so the
 * validation and failure paths can be tried as well as the happy path.
 */
export function OrderForm({ skus, disabled, serverFields, onSubmit }: OrderFormProps) {
  const id = useId()
  const [customerId, setCustomerId] = useState('cust-1')
  const [lines, setLines] = useState<Line[]>(() => [newLine()])
  const [localError, setLocalError] = useState<string | null>(null)

  const total = lines.reduce((sum, line) => sum + (Number(line.quantity) || 0) * (Number(line.price) || 0), 0)

  function update(key: number, patch: Partial<Line>) {
    setLines((current) => current.map((line) => {
      if (line.key !== key) return line
      const next = { ...line, ...patch }
      // Picking a known SKU suggests its usual price; typing a price keeps it.
      if (patch.skuCode !== undefined && SUGGESTED_PRICES[patch.skuCode] !== undefined) {
        next.price = String(SUGGESTED_PRICES[patch.skuCode])
      }
      return next
    }))
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const codes = lines.map((line) => line.skuCode.trim()).filter(Boolean)
    const duplicate = codes.find((code, index) => codes.indexOf(code) !== index)
    if (duplicate) {
      setLocalError(`${duplicate} appears twice. Use one line per SKU and set its quantity instead.`)
      return
    }
    setLocalError(null)
    onSubmit({
      customerId,
      items: lines.map((line) => ({
        skuCode: line.skuCode,
        quantity: Number.parseInt(line.quantity, 10),
        price: Number.parseFloat(line.price),
      })),
    })
  }

  const fieldError = (name: string) => serverFields[name]

  return (
    <form className="order-form" onSubmit={handleSubmit} noValidate>
      <datalist id={`${id}-skus`}>
        {skus.map((sku) => <option key={sku} value={sku} />)}
      </datalist>

      <label className="field">
        <span className="field__label">Customer ID</span>
        <input value={customerId} onChange={(event) => setCustomerId(event.target.value)} autoComplete="off" />
        {fieldError('customerId') && <span className="field__error">{fieldError('customerId')}</span>}
      </label>

      <fieldset className="order-form__lines">
        <legend className="field__label">Lines</legend>
        {lines.map((line, index) => (
          <div className="order-line" key={line.key}>
            <label className="field">
              <span className="field__label">SKU</span>
              <input
                className="mono"
                list={`${id}-skus`}
                value={line.skuCode}
                onChange={(event) => update(line.key, { skuCode: event.target.value })}
                autoComplete="off"
              />
              {fieldError(`items[${index}].skuCode`) && <span className="field__error">{fieldError(`items[${index}].skuCode`)}</span>}
            </label>
            <label className="field field--narrow">
              <span className="field__label">Qty</span>
              <input
                type="number"
                inputMode="numeric"
                min={1}
                step={1}
                value={line.quantity}
                onChange={(event) => update(line.key, { quantity: event.target.value })}
              />
              {fieldError(`items[${index}].quantity`) && <span className="field__error">{fieldError(`items[${index}].quantity`)}</span>}
            </label>
            <label className="field field--narrow">
              <span className="field__label">Unit price</span>
              <input
                type="number"
                inputMode="decimal"
                min={0.01}
                step="0.01"
                value={line.price}
                onChange={(event) => update(line.key, { price: event.target.value })}
              />
              {fieldError(`items[${index}].price`) && <span className="field__error">{fieldError(`items[${index}].price`)}</span>}
            </label>
            <button
              type="button"
              className="link-button order-line__remove"
              onClick={() => setLines((current) => current.filter((existing) => existing.key !== line.key))}
              disabled={lines.length === 1}
              aria-label={`Remove line ${index + 1}`}
            >
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="link-button" onClick={() => setLines((current) => [...current, newLine('PHONE-002')])}>
          Add a line
        </button>
        {fieldError('items') && <span className="field__error">{fieldError('items')}</span>}
      </fieldset>

      {localError && <p className="field__error" role="alert">{localError}</p>}

      <div className="order-form__footer">
        <span className="order-form__total">Total {formatAmount(total)}</span>
        <button type="submit" className="button" disabled={disabled}>Place order</button>
      </div>
      <p className="hint">
        Prices are sent by the browser and order-service accepts them as given. A production build would look them up
        in a catalog service.
      </p>
    </form>
  )
}
