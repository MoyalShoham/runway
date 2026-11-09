import { describe, expect, it } from 'vitest'
import { chartRows } from '../chart'
import { axisMoney, money, signedMoney } from '../format'
import { describe as describeScenario, emptyDraft, toScenario } from '../scenarios'
import type { Day } from '../types'

const day = (date: string, p10: string, p50: string, p90: string): Day => ({
  date,
  p10,
  p50,
  p90,
  probabilityBelowMinimum: 0,
})

describe('chartRows', () => {
  it('joins history and forecast at the last actual balance', () => {
    const rows = chartRows(
      [
        { date: '2025-06-29', balance: '1000.00' },
        { date: '2025-06-30', balance: '1200.00' },
      ],
      [day('2025-07-01', '900.00', '1100.00', '1300.00')],
    )
    expect(rows.map((r) => r.date)).toEqual(['2025-06-29', '2025-06-30', '2025-07-01'])
    expect(rows[1]).toMatchObject({ actual: 1200, median: 1200, band: [1200, 1200] })
    expect(rows[2]).toMatchObject({ median: 1100, band: [900, 1300] })
    expect(rows[2]?.actual).toBeUndefined()
  })

  it('adds the scenario series when given', () => {
    const rows = chartRows(
      [],
      [day('2025-07-01', '1', '2', '3')],
      [day('2025-07-01', '0', '1', '2')],
    )
    expect(rows[0]).toMatchObject({ scenarioMedian: 1, scenarioBand: [0, 2] })
  })
})

describe('toScenario', () => {
  it('requires a customer for a late payment and bounds the days', () => {
    expect(toScenario({ ...emptyDraft('CUSTOMER_PAYS_LATE') })).toBe('Pick a customer')
    expect(
      toScenario({ ...emptyDraft('CUSTOMER_PAYS_LATE'), customer: 'Acme', extraDays: '400' }),
    ).toMatch(/1 to 365/)
    expect(
      toScenario({ ...emptyDraft('CUSTOMER_PAYS_LATE'), customer: ' Acme ', extraDays: '30' }),
    ).toEqual({
      type: 'CUSTOMER_PAYS_LATE',
      customer: 'Acme',
      extraDays: 30,
    })
  })

  it('keeps amounts as decimal strings and rejects sloppy numbers', () => {
    const base = { ...emptyDraft('ONE_OFF'), date: '2025-08-01' }
    expect(toScenario({ ...base, amount: '-25000.50' })).toEqual({
      type: 'ONE_OFF',
      date: '2025-08-01',
      amount: '-25000.50',
    })
    expect(toScenario({ ...base, amount: '1,000' })).toMatch(/number/)
    expect(toScenario({ ...base, amount: '10.123' })).toMatch(/two decimals/)
    expect(toScenario({ ...base, amount: '0' })).toMatch(/zero/)
    expect(toScenario({ ...emptyDraft('NEW_HIRE'), date: '2025-08-01', amount: '-100' })).toMatch(
      /positive/,
    )
  })

  it('describes scenarios for the chip list', () => {
    expect(describeScenario({ type: 'CUSTOMER_PAYS_LATE', customer: 'Acme', extraDays: 30 })).toBe(
      'Acme pays 30 days later',
    )
  })
})

describe('format', () => {
  it('formats shekels without decimals and signs deltas', () => {
    expect(money('1234.56')).toContain('1,235')
    expect(signedMoney('500')).toMatch(/^\+/)
    expect(axisMoney(120_000)).toBe('₪120k')
    expect(axisMoney(-2_500_000)).toBe('-₪2.5m')
  })
})
