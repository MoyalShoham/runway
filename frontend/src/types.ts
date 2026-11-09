/** API types. Money arrives as decimal strings and is only converted to numbers for display. */
export type Money = string

export interface Company {
  id: number
  name: string
  currency: string
  openingBalance: Money
  openingDate: string
  minimumBalance: Money
  transactions: number
  lastTransactionDate: string | null
}

export interface Day {
  date: string
  p10: Money
  p50: Money
  p90: Money
  probabilityBelowMinimum: number
}

export interface Driver {
  label: string
  component: string
  date: string
  amount: Money
  reason: string
}

export interface Alert {
  kind: 'BELOW_MINIMUM' | 'BELOW_ZERO'
  severity: 'LIKELY' | 'POSSIBLE'
  threshold: Money
  probability: number
  date: string
  shortfall: Money
  lowestDate: string
  lowestBalance: Money
  message: string
  drivers: Driver[]
}

export interface ScheduleItem {
  date: string
  label: string
  component: string
  amount: Money
  probability: number
}

export interface ComponentTotal {
  component: string
  label: string
  amount: Money
}

export interface Recurring {
  label: string
  direction: 'IN' | 'OUT'
  cadence: string
  amount: Money
  lastSeen: string
  nextDate: string
  occurrences: number
  confidence: number
  active: boolean
  amountChange: { from: Money; to: Money; since: string } | null
}

export interface RecurringDocument {
  type: 'INVOICE' | 'BILL'
  label: string
  cadence: string
  amount: Money
  nextIssueDate: string
  termDays: number
  active: boolean
}

export interface Forecast {
  asOf: string
  startingBalance: Money
  minimumBalance: Money
  paths: number
  breachProbabilityMinimum: number
  breachProbabilityZero: number
  days: Day[]
  alerts: Alert[]
  schedule: ScheduleItem[]
  components: ComponentTotal[]
  recurring: Recurring[]
  recurringDocuments: RecurringDocument[]
  doubtfulInvoices: { number: string; counterparty: string; dueDate: string; amount: Money }[]
}

export interface BalancePoint {
  date: string
  balance: Money
}

export type ScenarioType = 'CUSTOMER_PAYS_LATE' | 'LOSE_CUSTOMER' | 'NEW_HIRE' | 'ONE_OFF'

export interface ScenarioItem {
  type: ScenarioType
  customer?: string
  extraDays?: number
  date?: string
  amount?: Money
  payDay?: number
  label?: string
}

export interface Comparison {
  baseline: Forecast
  scenario: Forecast
  endDifference: Money
  lowestBaseline: Money
  lowestScenario: Money
}

export interface ImportResult {
  rowsRead: number
  imported: number
  duplicates: number
  updated: number
  rejected: { line: number; message: string }[]
}

export interface Problem {
  title?: string
  detail?: string
  errors?: ({ line: number; message: string } | string)[]
}
