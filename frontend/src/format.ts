import type { Money } from './types'

const ils = new Intl.NumberFormat('en-IL', {
  style: 'currency',
  currency: 'ILS',
  maximumFractionDigits: 0,
})

/** Display only: the decimal string becomes a float just to be drawn or formatted. */
export const toNumber = (m: Money): number => Number(m)

export const money = (m: Money | number): string =>
  ils.format(typeof m === 'number' ? m : toNumber(m))

export const signedMoney = (m: Money): string => {
  const n = toNumber(m)
  return (n > 0 ? '+' : '') + ils.format(n)
}

export const percent = (p: number): string => `${Math.round(p * 100)}%`

const dateFormat = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})
const shortDate = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' })

export const date = (iso: string): string => dateFormat.format(new Date(`${iso}T00:00:00`))
export const shortDay = (iso: string): string => shortDate.format(new Date(`${iso}T00:00:00`))

export const cadence = (c: string): string =>
  ({
    WEEKLY: 'Weekly',
    BIWEEKLY: 'Every 2 weeks',
    MONTHLY: 'Monthly',
    BIMONTHLY: 'Every 2 months',
    QUARTERLY: 'Quarterly',
    YEARLY: 'Yearly',
  })[c] ?? c

/** Compact axis label: 120000 -> "₪120k". */
export const axisMoney = (n: number): string => {
  const abs = Math.abs(n)
  const sign = n < 0 ? '-' : ''
  if (abs >= 1_000_000) return `${sign}₪${(abs / 1_000_000).toFixed(1)}m`
  if (abs >= 1_000) return `${sign}₪${Math.round(abs / 1_000)}k`
  return `${sign}₪${Math.round(abs)}`
}
