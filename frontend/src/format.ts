const money = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const count = new Intl.NumberFormat('en-IN')

/** 150000 -> "1,50,000.00". The backend has no currency, so neither does the UI. */
export function formatAmount(value: number): string {
  return money.format(value)
}

export function formatCount(value: number): string {
  return count.format(value)
}

/** "14:03:07.412": millisecond precision, because saga steps are milliseconds apart. */
export function formatClock(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number, width = 2) => String(n).padStart(width, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`
}

/** Milliseconds between two ISO timestamps, as "+412 ms" or "+1.2 s". */
export function formatElapsed(fromIso: string, toIso: string): string {
  const ms = new Date(toIso).getTime() - new Date(fromIso).getTime()
  if (!Number.isFinite(ms)) return ''
  if (ms < 1000) return `+${Math.max(0, Math.round(ms))} ms`
  return `+${(ms / 1000).toFixed(1)} s`
}
