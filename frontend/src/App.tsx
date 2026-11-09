import { useEffect, useState } from 'react'
import { api, ApiError } from './api'
import { AlertsPanel } from './components/AlertsPanel'
import { ForecastChart } from './components/ForecastChart'
import { ImportPanel } from './components/ImportPanel'
import { ScenarioPanel } from './components/ScenarioPanel'
import { ComponentsTable, RecurringTable, ScheduleTable } from './components/Tables'
import { date, money, percent, toNumber } from './format'
import type { BalancePoint, Company, Forecast } from './types'

type Tab = 'forecast' | 'recurring' | 'scenarios' | 'import'

export default function App() {
  const [companies, setCompanies] = useState<Company[]>([])
  const [companiesVersion, setCompaniesVersion] = useState(0)
  const [picked, setPicked] = useState<number | null>(null)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [forecastVersion, setForecastVersion] = useState(0)
  const [tab, setTab] = useState<Tab>('forecast')
  const [error, setError] = useState<string | null>(null)
  const [creatingDemo, setCreatingDemo] = useState(false)
  const [showNew, setShowNew] = useState(false)

  const selected = picked ?? companies[0]?.id ?? null
  const company = companies.find((c) => c.id === selected)
  const forecast = loaded && loaded.id === selected ? loaded.forecast : null
  const history = loaded && loaded.id === selected ? loaded.history : []

  useEffect(() => {
    let cancelled = false
    api
      .companies()
      .then((list) => {
        if (!cancelled) setCompanies(list)
      })
      .catch(() => {
        if (!cancelled) setError('Cannot reach the runway API')
      })
    return () => {
      cancelled = true
    }
  }, [companiesVersion])

  useEffect(() => {
    if (selected === null) return
    let cancelled = false
    Promise.all([api.forecast(selected), api.history(selected)])
      .then(([f, h]) => {
        if (!cancelled) {
          setLoaded({ id: selected, version: forecastVersion, forecast: f, history: h })
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : 'Could not load the forecast')
      })
    return () => {
      cancelled = true
    }
  }, [selected, forecastVersion])

  const refresh = () => {
    setCompaniesVersion((v) => v + 1)
    setForecastVersion((v) => v + 1)
  }

  const demo = async () => {
    setCreatingDemo(true)
    try {
      const c = await api.demo(Math.floor(Math.random() * 1000) + 1)
      setPicked(c.id)
      setCompaniesVersion((v) => v + 1)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not create the demo company')
    } finally {
      setCreatingDemo(false)
    }
  }

  return (
    <div className="app">
      <header className="topbar">
        <h1>
          runway <span className="muted">cash-flow forecast</span>
        </h1>
        <div className="company-picker">
          {companies.length > 0 && (
            <select
              aria-label="Company"
              value={selected ?? ''}
              onChange={(e) => setPicked(Number(e.target.value))}
            >
              {companies.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
          <button type="button" onClick={() => void demo()} disabled={creatingDemo}>
            {creatingDemo ? 'Generating…' : 'Load a demo company'}
          </button>
          {companies.length > 0 && (
            <button type="button" onClick={() => setShowNew((v) => !v)} aria-expanded={showNew}>
              New company
            </button>
          )}
          {company && (
            <button
              type="button"
              className="danger-link"
              onClick={() => {
                if (window.confirm(`Delete ${company.name} and all its data?`)) {
                  api
                    .deleteCompany(company.id)
                    .then(() => {
                      setPicked(null)
                      setCompaniesVersion((v) => v + 1)
                    })
                    .catch((e: unknown) =>
                      setError(e instanceof ApiError ? e.message : 'Could not delete'),
                    )
                }
              }}
            >
              Delete
            </button>
          )}
        </div>
      </header>

      {error && (
        <p className="error banner" role="alert">
          {error}
        </p>
      )}

      {(companies.length === 0 || showNew) && !error && (
        <NewCompany
          onCreated={(c) => {
            setPicked(c.id)
            setShowNew(false)
            setCompaniesVersion((v) => v + 1)
          }}
        />
      )}

      {company && (
        <>
          <nav className="tabs" role="tablist">
            {(['forecast', 'recurring', 'scenarios', 'import'] as const).map((t) => (
              <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
                {
                  {
                    forecast: 'Forecast',
                    recurring: 'Recurring',
                    scenarios: 'What if',
                    import: 'Import',
                  }[t]
                }
              </button>
            ))}
          </nav>

          {tab === 'import' && <ImportPanel companyId={company.id} onImported={refresh} />}

          {tab !== 'import' && !forecast && <p className="muted">Simulating…</p>}

          {tab !== 'import' && forecast && (
            <main className="stack">
              {tab === 'forecast' && (
                <>
                  <Summary
                    key={company.id}
                    forecast={forecast}
                    company={company}
                    onMinimum={async (m) => {
                      await api.updateMinimum(company.id, m)
                      refresh()
                    }}
                  />
                  <AlertsPanel
                    alerts={forecast.alerts}
                    breach={forecast.breachProbabilityMinimum}
                  />
                  <ForecastChart
                    history={history}
                    days={forecast.days}
                    minimum={toNumber(forecast.minimumBalance)}
                    asOf={forecast.asOf}
                  />
                  <div className="columns">
                    <ScheduleTable items={forecast.schedule} />
                    <ComponentsTable components={forecast.components} />
                  </div>
                  {forecast.doubtfulInvoices.length > 0 && (
                    <p className="muted">
                      {forecast.doubtfulInvoices.length} invoice(s) more than 180 days overdue are
                      left out as doubtful.
                    </p>
                  )}
                </>
              )}
              {tab === 'recurring' && (
                <RecurringTable
                  items={forecast.recurring}
                  documents={forecast.recurringDocuments}
                />
              )}
              {tab === 'scenarios' && (
                <ScenarioPanel
                  key={company.id}
                  companyId={company.id}
                  customers={forecast.customers}
                  history={history}
                  minimum={toNumber(forecast.minimumBalance)}
                />
              )}
            </main>
          )}
        </>
      )}
      <footer className="muted">
        Forecasts are probabilistic estimates from your own history, not financial advice.
      </footer>
    </div>
  )
}

interface Loaded {
  id: number
  version: number
  forecast: Forecast
  history: BalancePoint[]
}

function Summary({
  forecast,
  company,
  onMinimum,
}: {
  forecast: Forecast
  company: Company
  onMinimum: (m: string) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(company.minimumBalance)
  const lowest = forecast.days.reduce(
    (a, b) => (toNumber(b.p50) < toNumber(a.p50) ? b : a),
    forecast.days[0]!,
  )
  const end = forecast.days.at(-1)!
  return (
    <section className="cards">
      <div className="card stat">
        <span>Balance on {date(forecast.asOf)}</span>
        <strong>{money(forecast.startingBalance)}</strong>
      </div>
      <div className="card stat">
        <span>Lowest expected (median)</span>
        <strong>{money(lowest.p50)}</strong>
        <small>{date(lowest.date)}</small>
      </div>
      <div className="card stat">
        <span>In 90 days</span>
        <strong>{money(end.p50)}</strong>
        <small>
          likely {money(end.p10)} to {money(end.p90)}
        </small>
      </div>
      <div className="card stat">
        <span>Chance of dipping below minimum</span>
        <strong>{percent(forecast.breachProbabilityMinimum)}</strong>
        {editing ? (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void onMinimum(value).then(() => setEditing(false))
            }}
          >
            <input
              aria-label="Minimum balance"
              inputMode="decimal"
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
            <button type="submit">Save</button>
          </form>
        ) : (
          <small>
            minimum {money(forecast.minimumBalance)}{' '}
            <button type="button" className="link" onClick={() => setEditing(true)}>
              change
            </button>
          </small>
        )}
      </div>
    </section>
  )
}

function NewCompany({ onCreated }: { onCreated: (c: Company) => void }) {
  const [form, setForm] = useState({
    name: '',
    openingBalance: '0',
    openingDate: '',
    minimumBalance: '0',
  })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm({ ...form, [k]: e.target.value })
  return (
    <section className="card">
      <h2>Start with your own data</h2>
      <p className="muted">
        Or load a demo company above to see runway with three years of synthetic history.
      </p>
      <form
        className="form-row"
        onSubmit={(e) => {
          e.preventDefault()
          api
            .createCompany(form)
            .then(onCreated)
            .catch((err: unknown) =>
              setError(err instanceof ApiError ? err.message : 'Could not create the company'),
            )
        }}
      >
        <label>
          Name
          <input required value={form.name} onChange={set('name')} />
        </label>
        <label>
          Opening balance (₪)
          <input
            required
            inputMode="decimal"
            value={form.openingBalance}
            onChange={set('openingBalance')}
          />
        </label>
        <label>
          Balance date
          <input required type="date" value={form.openingDate} onChange={set('openingDate')} />
        </label>
        <label>
          Minimum balance (₪)
          <input
            required
            inputMode="decimal"
            value={form.minimumBalance}
            onChange={set('minimumBalance')}
          />
        </label>
        <button type="submit" className="primary">
          Create
        </button>
      </form>
      {error && <p className="error">{error}</p>}
    </section>
  )
}
