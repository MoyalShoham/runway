import { toNumber } from './format'
import type { BalancePoint, Day } from './types'

export interface ChartRow {
  date: string
  actual?: number
  band?: [number, number]
  median?: number
  scenarioBand?: [number, number]
  scenarioMedian?: number
}

/** Merges history and forecast(s) into one row per date. */
export function chartRows(history: BalancePoint[], days: Day[], scenario?: Day[]): ChartRow[] {
  const rows = new Map<string, ChartRow>()
  const row = (date: string): ChartRow => {
    let r = rows.get(date)
    if (!r) {
      r = { date }
      rows.set(date, r)
    }
    return r
  }
  for (const h of history) row(h.date).actual = toNumber(h.balance)
  for (const d of days) {
    const r = row(d.date)
    r.band = [toNumber(d.p10), toNumber(d.p90)]
    r.median = toNumber(d.p50)
  }
  for (const d of scenario ?? []) {
    const r = row(d.date)
    r.scenarioBand = [toNumber(d.p10), toNumber(d.p90)]
    r.scenarioMedian = toNumber(d.p50)
  }
  // The forecast starts from the last actual balance, so the two lines touch.
  const last = history.at(-1)
  if (last && days.length > 0) {
    const r = row(last.date)
    const b = toNumber(last.balance)
    r.median = b
    r.band = [b, b]
    if (scenario) {
      r.scenarioMedian = b
      r.scenarioBand = [b, b]
    }
  }
  return [...rows.values()].sort((a, b) => a.date.localeCompare(b.date))
}
