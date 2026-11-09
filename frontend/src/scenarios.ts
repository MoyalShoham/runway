import { money, signedMoney } from './format'
import type { ScenarioItem, ScenarioType } from './types'

export interface Draft {
  type: ScenarioType
  customer: string
  extraDays: string
  date: string
  amount: string
  label: string
}

export const emptyDraft = (type: ScenarioType): Draft => ({
  type,
  customer: '',
  extraDays: '30',
  date: '',
  amount: '',
  label: '',
})

/** Turns a form draft into the API's scenario item; returns an error message when incomplete. */
export function toScenario(d: Draft): ScenarioItem | string {
  switch (d.type) {
    case 'CUSTOMER_PAYS_LATE': {
      const days = Number(d.extraDays)
      if (!d.customer.trim()) return 'Pick a customer'
      if (!Number.isInteger(days) || days < 1 || days > 365) return 'Extra days must be 1 to 365'
      return { type: d.type, customer: d.customer.trim(), extraDays: days }
    }
    case 'LOSE_CUSTOMER':
      if (!d.customer.trim()) return 'Pick a customer'
      if (!d.date) return 'Pick the date the customer leaves'
      return { type: d.type, customer: d.customer.trim(), date: d.date }
    case 'NEW_HIRE':
    case 'ONE_OFF': {
      if (!d.date) return 'Pick a date'
      if (!/^-?\d+(\.\d{1,2})?$/.test(d.amount.trim()))
        return 'Amount must be a number with up to two decimals'
      if (Number(d.amount) === 0) return 'Amount must not be zero'
      if (d.type === 'NEW_HIRE' && Number(d.amount) < 0) return 'Monthly cost is a positive number'
      const item: ScenarioItem = { type: d.type, date: d.date, amount: d.amount.trim() }
      if (d.label.trim()) item.label = d.label.trim()
      return item
    }
  }
}

export function describe(s: ScenarioItem): string {
  switch (s.type) {
    case 'CUSTOMER_PAYS_LATE':
      return `${s.customer} pays ${s.extraDays} days later`
    case 'LOSE_CUSTOMER':
      return `${s.customer} leaves from ${s.date}`
    case 'NEW_HIRE':
      return `${s.label ?? 'New hire'} from ${s.date}, ${money(s.amount ?? '0')}/month`
    case 'ONE_OFF':
      return `${s.label ?? 'One-off'} on ${s.date}: ${signedMoney(s.amount ?? '0')}`
  }
}
