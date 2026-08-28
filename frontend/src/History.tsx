import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  getHistory,
  PRIORITY_LABELS,
  type HistoryQuery,
  type TaskContext,
  type TaskView,
} from './api'
import TaskDetail from './TaskDetail'

const PAGE_SIZE = 50

/**
 * History over closed and cancelled tasks.
 *
 * <p>Open tasks are deliberately absent: they are all on the main page, which
 * has its own filter. Including them would mean a date range that applies to
 * closedAt for some rows and createdAt for others - a rule nobody remembers a
 * month later.
 */
export default function History() {
  // Draft holds what is typed; applied is what has been searched. Without the
  // split, every keystroke fires a request.
  const [draft, setDraft] = useState<HistoryQuery>({})
  const [applied, setApplied] = useState<HistoryQuery>({})
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)

  const { data, isPending, error } = useQuery({
    queryKey: ['history', applied, offset],
    queryFn: () => getHistory({ ...applied, limit: PAGE_SIZE, offset }),
  })

  const search = () => {
    setOffset(0)
    setApplied(draft)
  }

  const reset = () => {
    setOffset(0)
    setDraft({})
    setApplied({})
  }

  const set = <K extends keyof HistoryQuery>(key: K, value: HistoryQuery[K]) =>
    setDraft(d => ({ ...d, [key]: value === '' ? undefined : value }))

  const shown = data ? offset + data.items.length : 0

  return (
    <main className="page">
      <header className="header">
        <h1>History</h1>
        <a className="link" href="#/">Back to tasks</a>
      </header>

      <form
        className="history-filters"
        onSubmit={e => { e.preventDefault(); search() }}
      >
        <input
          className="grow"
          value={draft.q ?? ''}
          onChange={e => set('q', e.target.value)}
          placeholder="Search title and description"
          aria-label="Search text"
        />

        <label className="field">
          <span>From</span>
          <input type="date" value={draft.from ?? ''} onChange={e => set('from', e.target.value)} />
        </label>

        <label className="field">
          <span>To</span>
          <input type="date" value={draft.to ?? ''} onChange={e => set('to', e.target.value)} />
        </label>

        <select
          value={draft.status ?? ''}
          onChange={e => set('status', (e.target.value || undefined) as HistoryQuery['status'])}
          aria-label="Status"
        >
          <option value="">Done and cancelled</option>
          <option value="DONE">Done only</option>
          <option value="CANCELLED">Cancelled only</option>
        </select>

        <select
          value={draft.context ?? ''}
          onChange={e => set('context', (e.target.value || undefined) as TaskContext)}
          aria-label="Context"
        >
          <option value="">Any context</option>
          <option value="WORK">Work</option>
          <option value="PERSONAL">Personal</option>
        </select>

        <button type="submit">Search</button>
        <button type="button" className="link" onClick={reset}>Reset</button>
      </form>

      {error && <p className="error">{(error as Error).message}</p>}
      {isPending && <p className="empty">Loading...</p>}

      {data && (
        <>
          <p className="count-line">
            {data.total === 0
              ? 'Nothing matched.'
              : `Showing ${offset + 1}-${shown} of ${data.total}`}
            {!draft.from && !draft.to && data.total > 0 && ' (last 30 days)'}
          </p>

          <ul className="tasks">
            {data.items.map(task => (
              <li key={task.id}>
                <HistoryRow task={task} timezone={data.timezone} onOpen={() => setSelected(task.id)} />
              </li>
            ))}
          </ul>

          {data.total > PAGE_SIZE && (
            <nav className="pager">
              <button
                disabled={offset === 0}
                onClick={() => setOffset(o => Math.max(0, o - PAGE_SIZE))}
              >
                Previous
              </button>
              <button
                disabled={shown >= data.total}
                onClick={() => setOffset(o => o + PAGE_SIZE)}
              >
                Next
              </button>
            </nav>
          )}
        </>
      )}

      {selected && (
        <TaskDetail id={selected} onClose={() => setSelected(null)} onOpenOther={setSelected} />
      )}
    </main>
  )
}

function HistoryRow({ task, timezone, onOpen }: {
  task: TaskView
  timezone: string
  onOpen: () => void
}) {
  // Formatted in the server's timezone so a row never appears under a date the
  // server would not agree with.
  const closed = task.closedAt
    ? new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        day: '2-digit',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date(task.closedAt))
    : ''

  return (
    <div className="row">
      <span className="when">{closed}</span>
      <span className={`pri pri-${task.priority}`}>{PRIORITY_LABELS[task.priority]}</span>
      <button
        className={task.status === 'CANCELLED' ? 'title title-link cancelled' : 'title title-link'}
        onClick={onOpen}
      >
        {task.title}
      </button>
      {task.status === 'CANCELLED' && <span className="badge">cancelled</span>}
      <span className={task.context === 'WORK' ? 'ctx ctx-work' : 'ctx ctx-personal'}>
        {task.context === 'WORK' ? 'work' : 'personal'}
      </span>
    </div>
  )
}
