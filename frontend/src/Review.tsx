import { useQuery } from '@tanstack/react-query'
import { getWeeklyReview, type ContextCount, type OpenTask } from './api'

/**
 * The weekly review.
 *
 * The one page that actually gets read. A dashboard someone visits twice is
 * worthless; this answers four questions in one screen - what got finished,
 * whether the list grew, what is oldest, and what has been sitting untouched.
 *
 * It covers the last seven days rather than the previous calendar week, so it
 * says something useful whenever it is opened rather than only on a Monday.
 */
export default function Review() {
  const { data, isPending, error } = useQuery({ queryKey: ['weekly-review'], queryFn: getWeeklyReview })

  if (isPending) return <main className="page">Loading...</main>
  if (error) return <main className="page"><p className="error">{(error as Error).message}</p></main>

  const total = (rows: ContextCount[]) => rows.reduce((sum, r) => sum + r.count, 0)
  const doneTotal = total(data.done)
  const cancelledTotal = total(data.cancelled)

  return (
    <main className="page">
      <header className="header">
        <h1>Weekly review</h1>
        <nav className="header-links">
          <a className="link" href="#/analytics">Analytics</a>
          <a className="link" href="#/">Back to tasks</a>
        </nav>
      </header>

      <p className="viz-note">The last seven days.</p>

      <section className="kpis">
        <div className="kpi">
          <span className="kpi-value">{doneTotal}</span>
          <span className="kpi-label">completed</span>
          <span className="kpi-sub">{byContext(data.done)}</span>
        </div>
        <div className="kpi">
          <span className="kpi-value">{data.created}</span>
          <span className="kpi-label">created</span>
        </div>
        <div className={data.net > 0 ? 'kpi kpi-bad' : 'kpi kpi-good'}>
          <span className="kpi-value">{data.net > 0 ? `+${data.net}` : data.net}</span>
          <span className="kpi-label">net change</span>
          <span className="kpi-sub">
            {data.net > 0 ? 'the list grew' : data.net < 0 ? 'the list shrank' : 'level'}
          </span>
        </div>
        {cancelledTotal > 0 && (
          <div className="kpi">
            <span className="kpi-value">{cancelledTotal}</span>
            <span className="kpi-label">cancelled</span>
            <span className="kpi-sub">{byContext(data.cancelled)}</span>
          </div>
        )}
      </section>

      <TaskList
        title="Oldest still open"
        note="Not necessarily wrong, but they have been on the list longest."
        tasks={data.oldestOpen}
        empty="Nothing is open."
      />

      <TaskList
        title={`Untouched for over ${data.staleAfterDays} days`}
        note="Open, and with no change since the day it was created. Usually either badly written or something you have quietly decided not to do. Either way it deserves a decision rather than another week."
        tasks={data.stale}
        empty="Nothing has gone stale. "
      />
    </main>
  )
}

function byContext(rows: ContextCount[]): string {
  if (rows.length === 0) return ''
  return rows
    .map(r => `${r.count} ${r.context === 'WORK' ? 'work' : 'personal'}`)
    .join(', ')
}

function TaskList({ title, note, tasks, empty }: {
  title: string
  note: string
  tasks: OpenTask[]
  empty: string
}) {
  return (
    <section className="viz">
      <div className="viz-head"><h2>{title}</h2></div>
      <p className="viz-note">{note}</p>
      {tasks.length === 0 ? (
        <p className="empty">{empty}</p>
      ) : (
        <ul className="plain">
          {tasks.map(t => (
            <li key={t.id}>
              <span className="age-badge">{t.ageDays}d</span>
              <span className={`pri pri-${t.priority}`}>P{t.priority}</span>
              {t.title}
              <span className={t.context === 'WORK' ? 'ctx ctx-work' : 'ctx ctx-personal'}>
                {t.context === 'WORK' ? 'work' : 'personal'}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
