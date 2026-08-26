import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  formatDuration,
  getAging,
  getBacklog,
  getCycleTime,
  getThroughput,
  shortDate,
  type CycleTimeRow,
} from './api'

/**
 * Tier 1 analytics.
 *
 * Colour follows the validated palette: two categorical slots (blue, orange)
 * for work/personal and created/closed, and a single-hue ordinal ramp for the
 * age bands. Trailing averages are drawn in secondary ink rather than a third
 * hue, because they are context for the bars, not a third thing being compared.
 *
 * Every chart has a table view. Three of the palette steps sit under 3:1 on a
 * white surface, and the relief rule for that is a readable table - not a
 * darker blue that fails the lightness band.
 */
export default function Analytics() {
  return (
    <main className="page wide">
      <header className="header">
        <h1>Analytics</h1>
        <nav className="header-links">
          <a className="link" href="#/review">Weekly review</a>
          <a className="link" href="#/">Back to tasks</a>
        </nav>
      </header>

      <ThroughputChart />
      <BacklogChart />
      <AgingChart />
      <CycleTimeTable />
    </main>
  )
}

// ---- shared chrome ---------------------------------------------------------

function Section({ title, note, table, children }: {
  title: string
  note: string
  table: ReactNode
  children: ReactNode
}) {
  const [showTable, setShowTable] = useState(false)
  return (
    <section className="viz">
      <div className="viz-head">
        <h2>{title}</h2>
        <button className="link" onClick={() => setShowTable(v => !v)}>
          {showTable ? 'Show chart' : 'Show data table'}
        </button>
      </div>
      <p className="viz-note">{note}</p>
      {showTable ? <div className="viz-table">{table}</div> : children}
    </section>
  )
}

const AXIS = { stroke: 'var(--chart-axis)', fontSize: 11, tickLine: false }
const TOOLTIP = {
  contentStyle: {
    background: 'var(--bg)',
    border: '1px solid var(--line)',
    borderRadius: '6px',
    fontSize: '0.8rem',
    color: 'var(--fg)',
  },
  labelStyle: { color: 'var(--fg)' },
}

function Loading() {
  return <p className="empty">Loading...</p>
}

function Failed({ error }: { error: unknown }) {
  return <p className="error">{(error as Error).message}</p>
}

// ---- 1. throughput ---------------------------------------------------------

function ThroughputChart() {
  const { data, isPending, error } = useQuery({ queryKey: ['throughput'], queryFn: () => getThroughput(90) })
  if (isPending) return <Loading />
  if (error) return <Failed error={error} />

  const rows = data.points.map(p => ({ ...p, label: shortDate(p.day, data.timezone) }))

  return (
    <Section
      title="Throughput"
      note="Tasks completed per day, split work and personal. The line is a trailing 7-day average - a single bad Tuesday is not a trend."
      table={
        <table>
          <thead><tr><th>Day</th><th>Work</th><th>Personal</th><th>Cancelled</th><th>7d avg</th></tr></thead>
          <tbody>
            {rows.slice().reverse().map(r => (
              <tr key={r.day}>
                <td>{r.label}</td><td>{r.workDone}</td><td>{r.personalDone}</td>
                <td>{r.cancelled}</td><td>{r.trailing7.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      <ResponsiveContainer width="100%" height={240}>
        <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -20 }}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis dataKey="label" {...AXIS} interval="preserveStartEnd" minTickGap={40} />
          <YAxis {...AXIS} allowDecimals={false} />
          <Tooltip {...TOOLTIP} />
          <Legend wrapperStyle={{ fontSize: '0.78rem' }} />
          {/* 2px surface gap between stacked segments, per the mark spec. */}
          <Bar dataKey="workDone" name="Work" stackId="done" fill="var(--series-1)"
               stroke="var(--bg)" strokeWidth={2} />
          <Bar dataKey="personalDone" name="Personal" stackId="done" fill="var(--series-2)"
               stroke="var(--bg)" strokeWidth={2} radius={[4, 4, 0, 0]} />
          <Line dataKey="trailing7" name="7-day average" type="monotone"
                stroke="var(--chart-ink)" strokeWidth={2} dot={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </Section>
  )
}

// ---- 2. net backlog --------------------------------------------------------

function BacklogChart() {
  const { data, isPending, error } = useQuery({ queryKey: ['backlog'], queryFn: () => getBacklog(12) })
  if (isPending) return <Loading />
  if (error) return <Failed error={error} />

  const latest = data.at(-1)

  return (
    <Section
      title="Net backlog"
      note="Created against closed, per week. If the bars on the left run taller than the bars on the right, the list is growing."
      table={
        <table>
          <thead><tr><th>Week of</th><th>Created</th><th>Closed</th><th>Net</th><th>Cumulative</th></tr></thead>
          <tbody>
            {data.slice().reverse().map(w => (
              <tr key={w.week}>
                <td>{w.week}</td><td>{w.created}</td><td>{w.closed}</td>
                <td>{w.net > 0 ? `+${w.net}` : w.net}</td><td>{w.cumulative}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {latest && (
        <p className={latest.net > 0 ? 'stat stat-bad' : 'stat stat-good'}>
          <strong>{latest.net > 0 ? `+${latest.net}` : latest.net}</strong> net this week
          <span className="stat-sub">
            {latest.net > 0 ? 'the open list grew' : latest.net < 0 ? 'the open list shrank' : 'level'}
          </span>
        </p>
      )}
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -20 }} barGap={2}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis dataKey="week" {...AXIS} interval="preserveStartEnd" minTickGap={30} />
          <YAxis {...AXIS} allowDecimals={false} />
          <Tooltip {...TOOLTIP} />
          <Legend wrapperStyle={{ fontSize: '0.78rem' }} />
          <Bar dataKey="created" name="Created" fill="var(--series-2)" radius={[4, 4, 0, 0]} />
          <Bar dataKey="closed" name="Closed" fill="var(--series-1)" radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </Section>
  )
}

// ---- 3. aging WIP ----------------------------------------------------------

const BAND_FILL = ['var(--band-1)', 'var(--band-2)', 'var(--band-3)', 'var(--band-4)']

function AgingChart() {
  const { data, isPending, error } = useQuery({ queryKey: ['aging'], queryFn: getAging })
  if (isPending) return <Loading />
  if (error) return <Failed error={error} />

  return (
    <Section
      title="Aging work in progress"
      note="Open tasks by age. The oldest band is the one worth reading - a count is interesting, the titles below are actionable."
      table={
        <table>
          <thead><tr><th>Age</th><th>Open tasks</th></tr></thead>
          <tbody>
            {data.buckets.map(b => <tr key={b.bucket}><td>{b.bucket}</td><td>{b.count}</td></tr>)}
          </tbody>
        </table>
      }
    >
      <ResponsiveContainer width="100%" height={150}>
        <BarChart data={data.buckets} layout="vertical" margin={{ top: 4, right: 40, bottom: 0, left: 10 }}>
          <CartesianGrid stroke="var(--chart-grid)" horizontal={false} />
          <XAxis type="number" {...AXIS} allowDecimals={false} />
          <YAxis type="category" dataKey="bucket" {...AXIS} width={52} />
          <Tooltip {...TOOLTIP} cursor={{ fill: 'var(--chart-grid)' }} />
          <Bar dataKey="count" name="Open tasks" radius={[0, 4, 4, 0]} label={{ position: 'right', fill: 'var(--chart-ink)', fontSize: 11 }}>
            {data.buckets.map((b, i) => <Cell key={b.bucket} fill={BAND_FILL[i]} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>

      {data.oldest.length > 0 && (
        <>
          <h3 className="viz-sub">Oldest open</h3>
          <ul className="plain">
            {data.oldest.map(t => (
              <li key={t.id}>
                <span className="age-badge">{t.ageDays}d</span> {t.title}
                <span className={t.context === 'WORK' ? 'ctx ctx-work' : 'ctx ctx-personal'}>
                  {t.context === 'WORK' ? 'work' : 'personal'}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </Section>
  )
}

// ---- 4. cycle time ---------------------------------------------------------

function CycleTimeTable() {
  const { data, isPending, error } = useQuery({ queryKey: ['cycle-time'], queryFn: () => getCycleTime(180) })
  if (isPending) return <Loading />
  if (error) return <Failed error={error} />

  const group = (dimension: CycleTimeRow['dimension']) => data.filter(r => r.dimension === dimension)
  const overall = group('OVERALL')[0]

  return (
    <section className="viz">
      <div className="viz-head"><h2>Cycle time</h2></div>
      <p className="viz-note">
        How long a task takes from created to done, over the last 180 days. Median and p90, never
        an average - one task left open for months would drag a mean somewhere meaningless. A
        table rather than a chart: four numbers do not need axes.
      </p>

      {!overall || overall.count === 0 ? (
        <p className="empty">Nothing completed yet in this window.</p>
      ) : (
        <table className="viz-table">
          <thead>
            <tr><th>Group</th><th>Completed</th><th>Median</th><th>p90</th></tr>
          </thead>
          <tbody>
            <tr className="row-strong">
              <td>All</td><td>{overall.count}</td>
              <td>{formatDuration(overall.p50Seconds)}</td>
              <td>{formatDuration(overall.p90Seconds)}</td>
            </tr>
            {group('PRIORITY').map(r => (
              <tr key={r.bucket}>
                <td>{r.bucket}</td><td>{r.count}</td>
                <td>{formatDuration(r.p50Seconds)}</td><td>{formatDuration(r.p90Seconds)}</td>
              </tr>
            ))}
            {group('CONTEXT').map(r => (
              <tr key={r.bucket}>
                <td>{r.bucket === 'WORK' ? 'Work' : 'Personal'}</td><td>{r.count}</td>
                <td>{formatDuration(r.p50Seconds)}</td><td>{formatDuration(r.p90Seconds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="viz-note">
        If P3 closes as fast as P0, the priority field is decorative and you can stop filling it in.
      </p>
    </section>
  )
}
