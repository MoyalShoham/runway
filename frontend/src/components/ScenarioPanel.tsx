import { useState } from 'react'
import { api, ApiError } from '../api'
import { money, signedMoney } from '../format'
import { describe, emptyDraft, toScenario, type Draft } from '../scenarios'
import type { BalancePoint, Comparison, ScenarioItem, ScenarioType } from '../types'
import { AlertsPanel } from './AlertsPanel'
import { ForecastChart } from './ForecastChart'

const LABELS: Record<ScenarioType, string> = {
  CUSTOMER_PAYS_LATE: 'A customer pays late',
  LOSE_CUSTOMER: 'Lose a customer',
  NEW_HIRE: 'New hire',
  ONE_OFF: 'One-off payment or receipt',
}

interface Props {
  companyId: number
  customers: string[]
  history: BalancePoint[]
  minimum: number
}

export function ScenarioPanel({ companyId, customers, history, minimum }: Props) {
  const [items, setItems] = useState<ScenarioItem[]>([])
  const [draft, setDraft] = useState<Draft>(emptyDraft('CUSTOMER_PAYS_LATE'))
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<Comparison | null>(null)
  const [running, setRunning] = useState(false)

  const add = () => {
    const s = toScenario(draft)
    if (typeof s === 'string') {
      setError(s)
      return
    }
    setError(null)
    setItems([...items, s])
    setDraft(emptyDraft(draft.type))
  }

  const run = async () => {
    setRunning(true)
    setError(null)
    try {
      setResult(await api.scenarios(companyId, items))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not run the scenario')
    } finally {
      setRunning(false)
    }
  }

  const set = (field: keyof Draft) => (e: { target: { value: string } }) =>
    setDraft({ ...draft, [field]: e.target.value })

  return (
    <div className="stack">
      <section className="card">
        <h2>What if…</h2>
        <div className="form-row">
          <label>
            Change
            <select
              value={draft.type}
              onChange={(e) => setDraft(emptyDraft(e.target.value as ScenarioType))}
            >
              {Object.entries(LABELS).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          {(draft.type === 'CUSTOMER_PAYS_LATE' || draft.type === 'LOSE_CUSTOMER') && (
            <label>
              Customer
              <input list="customers" value={draft.customer} onChange={set('customer')} />
              <datalist id="customers">
                {customers.map((c) => (
                  <option key={c} value={c} />
                ))}
              </datalist>
            </label>
          )}
          {draft.type === 'CUSTOMER_PAYS_LATE' && (
            <label>
              Extra days late
              <input
                type="number"
                min={1}
                max={365}
                value={draft.extraDays}
                onChange={set('extraDays')}
              />
            </label>
          )}
          {draft.type !== 'CUSTOMER_PAYS_LATE' && (
            <label>
              {draft.type === 'NEW_HIRE'
                ? 'Start date'
                : draft.type === 'LOSE_CUSTOMER'
                  ? 'From'
                  : 'Date'}
              <input type="date" value={draft.date} onChange={set('date')} />
            </label>
          )}
          {(draft.type === 'NEW_HIRE' || draft.type === 'ONE_OFF') && (
            <>
              <label>
                {draft.type === 'NEW_HIRE'
                  ? 'Monthly employer cost (₪)'
                  : 'Amount (₪, negative = payment)'}
                <input inputMode="decimal" value={draft.amount} onChange={set('amount')} />
              </label>
              <label>
                Label
                <input value={draft.label} onChange={set('label')} maxLength={100} />
              </label>
            </>
          )}
          <button type="button" onClick={add}>
            Add
          </button>
        </div>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {items.length > 0 && (
          <ul className="chips">
            {items.map((s, i) => (
              <li key={i}>
                {describe(s)}
                <button
                  type="button"
                  aria-label="remove"
                  onClick={() => setItems(items.filter((_, j) => j !== i))}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
        <button
          type="button"
          className="primary"
          disabled={items.length === 0 || running}
          onClick={run}
        >
          {running ? 'Running…' : 'Compare with baseline'}
        </button>
      </section>
      {result && (
        <>
          <section className="cards">
            <div className="card stat">
              <span>Difference after 90 days</span>
              <strong className={Number(result.endDifference) < 0 ? 'neg' : 'pos'}>
                {signedMoney(result.endDifference)}
              </strong>
            </div>
            <div className="card stat">
              <span>Lowest median, baseline</span>
              <strong>{money(result.lowestBaseline)}</strong>
            </div>
            <div className="card stat">
              <span>Lowest median, scenario</span>
              <strong>{money(result.lowestScenario)}</strong>
            </div>
          </section>
          <ForecastChart
            history={history}
            days={result.baseline.days}
            scenario={result.scenario.days}
            minimum={minimum}
            asOf={result.baseline.asOf}
          />
          <AlertsPanel
            alerts={result.scenario.alerts}
            breach={result.scenario.breachProbabilityMinimum}
          />
        </>
      )}
    </div>
  )
}
