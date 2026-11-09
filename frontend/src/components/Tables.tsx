import { cadence, date, money, percent, signedMoney } from '../format'
import type { ComponentTotal, Recurring, RecurringDocument, ScheduleItem } from '../types'

export function ScheduleTable({ items, limit = 25 }: { items: ScheduleItem[]; limit?: number }) {
  const shown = items.filter((i) => i.component !== 'IRREGULAR').slice(0, limit)
  return (
    <table>
      <caption>Upcoming items</caption>
      <thead>
        <tr>
          <th>Expected</th>
          <th>Item</th>
          <th className="num">Amount</th>
          <th className="num">Likelihood</th>
        </tr>
      </thead>
      <tbody>
        {shown.map((i) => (
          <tr key={`${i.label}-${i.date}`}>
            <td>{date(i.date)}</td>
            <td>{i.label}</td>
            <td className={`num ${Number(i.amount) < 0 ? 'neg' : 'pos'}`}>
              {signedMoney(i.amount)}
            </td>
            <td className="num">{percent(i.probability)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function ComponentsTable({ components }: { components: ComponentTotal[] }) {
  return (
    <table>
      <caption>Next 90 days by source (expected)</caption>
      <tbody>
        {components
          .filter((c) => Number(c.amount) !== 0)
          .map((c) => (
            <tr key={c.component}>
              <td>{c.label}</td>
              <td className={`num ${Number(c.amount) < 0 ? 'neg' : 'pos'}`}>
                {signedMoney(c.amount)}
              </td>
            </tr>
          ))}
      </tbody>
    </table>
  )
}

export function RecurringTable({
  items,
  documents,
}: {
  items: Recurring[]
  documents: RecurringDocument[]
}) {
  const sorted = [...items].sort(
    (a, b) => Number(b.active) - Number(a.active) || Number(b.amount) - Number(a.amount),
  )
  return (
    <>
      <table>
        <caption>Recurring bank flows</caption>
        <thead>
          <tr>
            <th>Name</th>
            <th>Cadence</th>
            <th className="num">Amount</th>
            <th>Next</th>
            <th>Confidence</th>
            <th>Notes</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr
              key={`${r.label}-${r.direction}-${r.cadence}-${r.amount}`}
              className={r.active ? '' : 'inactive'}
            >
              <td>{r.label}</td>
              <td>{cadence(r.cadence)}</td>
              <td className={`num ${r.direction === 'OUT' ? 'neg' : 'pos'}`}>
                {r.direction === 'OUT' ? '−' : '+'}
                {money(r.amount)}
              </td>
              <td>{r.active ? date(r.nextDate) : 'stopped'}</td>
              <td>
                <meter
                  min={0}
                  max={1}
                  value={r.confidence}
                  aria-label={`confidence ${percent(r.confidence)}`}
                />
              </td>
              <td className="muted">
                {r.amountChange &&
                  `changed from ${money(r.amountChange.from)} to ${money(r.amountChange.to)} on ${date(r.amountChange.since)}`}
                {!r.active && `last seen ${date(r.lastSeen)}`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {documents.length > 0 && (
        <table>
          <caption>Recurring invoices and bills</caption>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Cadence</th>
              <th className="num">Amount</th>
              <th>Next issue</th>
              <th className="num">Terms</th>
            </tr>
          </thead>
          <tbody>
            {documents.map((d) => (
              <tr key={`${d.type}-${d.label}-${d.amount}`} className={d.active ? '' : 'inactive'}>
                <td>{d.label}</td>
                <td>{d.type === 'INVOICE' ? 'Invoice' : 'Bill'}</td>
                <td>{cadence(d.cadence)}</td>
                <td className="num">{money(d.amount)}</td>
                <td>{d.active ? date(d.nextIssueDate) : 'stopped'}</td>
                <td className="num">{d.termDays} days</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
