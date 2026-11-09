import type {
  BalancePoint,
  Company,
  Comparison,
  Forecast,
  ImportResult,
  Problem,
  ScenarioItem,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? `Request failed (${status})`)
    this.status = status
    this.problem = problem
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, init)
  if (!response.ok) {
    let problem: Problem = {}
    try {
      problem = (await response.json()) as Problem
    } catch {
      // not JSON: keep the generic message
    }
    throw new ApiError(response.status, problem)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const api = {
  companies: () => request<Company[]>('/companies'),
  createCompany: (c: {
    name: string
    openingBalance: string
    openingDate: string
    minimumBalance: string
  }) => request<Company>('/companies', json(c)),
  updateMinimum: (id: number, minimumBalance: string) =>
    request<Company>(`/companies/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ minimumBalance }),
    }),
  demo: (seed: number) => request<Company>(`/demo?seed=${seed}`, { method: 'POST' }),
  forecast: (id: number) => request<Forecast>(`/companies/${id}/forecast`),
  history: (id: number, days = 120) =>
    request<BalancePoint[]>(`/companies/${id}/history?days=${days}`),
  scenarios: (id: number, scenarios: ScenarioItem[]) =>
    request<Comparison>(`/companies/${id}/scenarios`, json({ scenarios })),
  upload: (
    id: number,
    kind: 'transactions' | 'invoices' | 'bills',
    file: File,
    skipInvalid: boolean,
  ) => {
    const body = new FormData()
    body.append('file', file)
    return request<ImportResult>(`/companies/${id}/${kind}/import?skipInvalid=${skipInvalid}`, {
      method: 'POST',
      body,
    })
  },
}
