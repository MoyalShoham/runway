import { useState } from 'react'
import { api, ApiError } from '../api'
import type { ImportResult } from '../types'

type Kind = 'transactions' | 'invoices' | 'bills'

const HELP: Record<Kind, string> = {
  transactions:
    'date, amount (or debit and credit), description, counterparty. Hebrew headers work too.',
  invoices:
    'number, customer, issue_date, due_date, amount (incl. VAT), paid_date (empty if open).',
  bills: 'number, vendor, issue_date, due_date, amount (incl. VAT), paid_date (empty if open).',
}

interface Outcome {
  ok?: ImportResult
  error?: string
  errors?: { line: number; message: string }[]
}

export function ImportPanel({
  companyId,
  onImported,
}: {
  companyId: number
  onImported: () => void
}) {
  const [skipInvalid, setSkipInvalid] = useState(false)
  const [outcomes, setOutcomes] = useState<Partial<Record<Kind, Outcome>>>({})

  const upload = async (kind: Kind, file: File | undefined) => {
    if (!file) return
    try {
      const ok = await api.upload(companyId, kind, file, skipInvalid)
      setOutcomes((o) => ({ ...o, [kind]: { ok } }))
      onImported()
    } catch (e) {
      const errors =
        e instanceof ApiError
          ? (e.problem.errors ?? []).filter(
              (x): x is { line: number; message: string } => typeof x !== 'string',
            )
          : []
      setOutcomes((o) => ({
        ...o,
        [kind]: { error: e instanceof Error ? e.message : 'Upload failed', errors },
      }))
    }
  }

  return (
    <section className="card stack">
      <h2>Import CSV</h2>
      <label className="check">
        <input
          type="checkbox"
          checked={skipInvalid}
          onChange={(e) => setSkipInvalid(e.target.checked)}
        />
        Import valid rows even if some rows are invalid
      </label>
      {(['transactions', 'invoices', 'bills'] as const).map((kind) => {
        const o = outcomes[kind]
        return (
          <div key={kind} className="upload">
            <label>
              <strong>{kind[0]?.toUpperCase() + kind.slice(1)}</strong>
              <input
                type="file"
                accept=".csv,text/csv,text/plain"
                onChange={(e) => {
                  void upload(kind, e.target.files?.[0])
                  e.target.value = ''
                }}
              />
            </label>
            <p className="muted">{HELP[kind]}</p>
            {o?.ok && (
              <p className="pos">
                {o.ok.imported} imported
                {kind === 'transactions'
                  ? `, ${o.ok.duplicates} already there`
                  : `, ${o.ok.updated} updated`}
                {o.ok.rejected.length > 0 && `, ${o.ok.rejected.length} rejected`}
              </p>
            )}
            {o?.error && (
              <div className="error" role="alert">
                <p>{o.error}</p>
                {o.errors && o.errors.length > 0 && (
                  <ul>
                    {o.errors.slice(0, 10).map((r) => (
                      <li key={r.line}>
                        Line {r.line}: {r.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </div>
        )
      })}
    </section>
  )
}
