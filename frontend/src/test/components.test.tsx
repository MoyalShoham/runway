import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AlertsPanel } from '../components/AlertsPanel'
import type { Alert, Company, Forecast } from '../types'

const alert: Alert = {
  kind: 'BELOW_MINIMUM',
  severity: 'LIKELY',
  threshold: '20000.00',
  probability: 0.82,
  date: '2025-08-10',
  shortfall: '14500.00',
  lowestDate: '2025-08-15',
  lowestBalance: '9000.00',
  message: 'The balance is likely to fall below your minimum of ILS 20,000 by 10 Aug 2025.',
  drivers: [
    {
      label: 'VAT Mar 2025 – Apr 2025',
      component: 'VAT',
      date: '2025-05-15',
      amount: '-18000.00',
      reason: 'due before the shortfall',
    },
  ],
}

describe('AlertsPanel', () => {
  it('shows when, how much and why', () => {
    render(<AlertsPanel alerts={[alert]} breach={0.82} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Below your minimum by 10 Aug 2025')
    expect(screen.getByText('82% chance')).toBeInTheDocument()
    expect(screen.getByText(/VAT Mar 2025/)).toBeInTheDocument()
    expect(screen.getByText(/14,500/)).toBeInTheDocument()
  })

  it('says so when nothing is expected', () => {
    render(<AlertsPanel alerts={[]} breach={0.04} />)
    expect(screen.getByText('No shortfall expected')).toBeInTheDocument()
  })
})

const company: Company = {
  id: 1,
  name: 'Levi Consulting (demo)',
  currency: 'ILS',
  openingBalance: '50000.00',
  openingDate: '2022-12-31',
  minimumBalance: '20000.00',
  transactions: 1500,
  lastTransactionDate: '2025-06-30',
}

const forecast: Forecast = {
  asOf: '2025-06-30',
  startingBalance: '61000.00',
  minimumBalance: '20000.00',
  paths: 400,
  breachProbabilityMinimum: 0.82,
  breachProbabilityZero: 0.1,
  days: [
    {
      date: '2025-07-01',
      p10: '50000.00',
      p50: '58000.00',
      p90: '64000.00',
      probabilityBelowMinimum: 0,
    },
    {
      date: '2025-07-02',
      p10: '10000.00',
      p50: '15000.00',
      p90: '30000.00',
      probabilityBelowMinimum: 0.8,
    },
  ],
  alerts: [alert],
  schedule: [
    {
      date: '2025-07-09',
      label: 'Dana Levi',
      component: 'RECURRING',
      amount: '-14500.00',
      probability: 1,
    },
  ],
  components: [{ component: 'RECURRING', label: 'Recurring items', amount: '-40000.00' }],
  recurring: [],
  recurringDocuments: [],
  doubtfulInvoices: [],
  customers: ['Acme'],
}

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('loads the first company and shows its forecast', async () => {
    const fetchMock = vi.fn((url: string) => {
      const body = url.endsWith('/companies')
        ? [company]
        : url.includes('/forecast')
          ? forecast
          : url.includes('/history')
            ? [{ date: '2025-06-30', balance: '61000.00' }]
            : {}
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    await waitFor(() =>
      expect(screen.getByText('Below your minimum by 10 Aug 2025')).toBeInTheDocument(),
    )
    expect(screen.getByText('Dana Levi')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/companies/1/forecast', undefined)

    await userEvent.click(screen.getByRole('tab', { name: 'Recurring' }))
    expect(screen.getByText('Recurring bank flows')).toBeInTheDocument()
  })

  it('reports an unreachable API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('network'))),
    )
    render(<App />)
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Cannot reach the runway API'),
    )
  })
})

describe('api client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the client header on writes but not on reads', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await import('../api')

    await api.demo(3)
    await api.companies()

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[1]?.headers).toMatchObject({ 'X-Runway-Client': 'web' })
    expect(calls[1]?.[1]).toBeUndefined()
  })
})
