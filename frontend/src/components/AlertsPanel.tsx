import { date, money, percent, signedMoney } from '../format'
import type { Alert } from '../types'

export function AlertsPanel({ alerts, breach }: { alerts: Alert[]; breach: number }) {
  if (alerts.length === 0) {
    return (
      <section className="card ok" aria-label="Alerts">
        <h2>No shortfall expected</h2>
        <p>
          {percent(breach)} of simulated paths dip below your minimum in the next 90 days, under the
          alert threshold.
        </p>
      </section>
    )
  }
  return (
    <section aria-label="Alerts" className="alerts">
      {alerts.map((a) => (
        <article
          key={a.kind}
          className={`card alert ${a.severity === 'LIKELY' ? 'danger' : 'warn'}`}
          role="alert"
        >
          <header>
            <h2>
              {a.kind === 'BELOW_ZERO' ? 'Overdraft risk' : 'Below your minimum'} by {date(a.date)}
            </h2>
            <span className="pill">{percent(a.probability)} chance</span>
          </header>
          <p>{a.message}</p>
          <dl className="facts">
            <div>
              <dt>Cash needed now</dt>
              <dd>{money(a.shortfall)}</dd>
            </div>
            <div>
              <dt>Lowest median balance</dt>
              <dd>
                {money(a.lowestBalance)} on {date(a.lowestDate)}
              </dd>
            </div>
          </dl>
          {a.drivers.length > 0 && (
            <>
              <h3>What drives it</h3>
              <ul className="drivers">
                {a.drivers.map((d) => (
                  <li key={`${d.label}-${d.date}`}>
                    <span>{d.label}</span>
                    <span className="muted">
                      {date(d.date)}, {d.reason}
                    </span>
                    <span className={Number(d.amount) < 0 ? 'neg' : 'pos'}>
                      {signedMoney(d.amount)}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </article>
      ))}
    </section>
  )
}
