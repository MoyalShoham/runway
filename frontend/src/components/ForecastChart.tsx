import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { chartRows } from '../chart'
import { axisMoney, money, shortDay } from '../format'
import type { BalancePoint, Day } from '../types'

interface Props {
  history: BalancePoint[]
  days: Day[]
  minimum: number
  asOf: string
  scenario?: Day[]
  height?: number
}

export function ForecastChart({ history, days, minimum, asOf, scenario, height = 360 }: Props) {
  const data = chartRows(history, days, scenario)
  return (
    <figure className="chart" aria-label="Balance history and forecast">
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={data} margin={{ top: 12, right: 16, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis dataKey="date" tickFormatter={shortDay} minTickGap={40} stroke="var(--muted)" />
          <YAxis tickFormatter={axisMoney} width={64} stroke="var(--muted)" />
          <Tooltip
            formatter={(value, name) => {
              const v = Array.isArray(value)
                ? `${money(Number(value[0]))} – ${money(Number(value[1]))}`
                : money(Number(value))
              return [v, String(name)]
            }}
            labelFormatter={(l) => shortDay(String(l))}
          />
          <Legend />
          <Area
            dataKey="band"
            name={scenario ? 'Baseline 10–90%' : 'Likely range (10–90%)'}
            stroke="none"
            fill="var(--band)"
            fillOpacity={scenario ? 0.35 : 0.6}
            isAnimationActive={false}
          />
          {scenario && (
            <Area
              dataKey="scenarioBand"
              name="Scenario 10–90%"
              stroke="none"
              fill="var(--scenario-band)"
              fillOpacity={0.45}
              isAnimationActive={false}
            />
          )}
          <Line
            dataKey="actual"
            name="Actual balance"
            stroke="var(--ink)"
            dot={false}
            strokeWidth={2}
            isAnimationActive={false}
          />
          <Line
            dataKey="median"
            name={scenario ? 'Baseline median' : 'Forecast median'}
            stroke="var(--accent)"
            {...(scenario ? { strokeDasharray: '4 3' } : {})}
            dot={false}
            strokeWidth={2}
            isAnimationActive={false}
          />
          {scenario && (
            <Line
              dataKey="scenarioMedian"
              name="Scenario median"
              stroke="var(--scenario)"
              dot={false}
              strokeWidth={2}
              isAnimationActive={false}
            />
          )}
          <ReferenceLine
            y={minimum}
            stroke="var(--warn)"
            strokeDasharray="6 4"
            label={{
              value: 'Minimum',
              position: 'insideTopLeft',
              fill: 'var(--warn)',
              fontSize: 12,
            }}
          />
          <ReferenceLine y={0} stroke="var(--danger)" />
          <ReferenceLine x={asOf} stroke="var(--muted)" strokeDasharray="2 4" />
        </ComposedChart>
      </ResponsiveContainer>
    </figure>
  )
}
