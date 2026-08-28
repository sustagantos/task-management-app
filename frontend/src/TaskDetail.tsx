import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ageInDays,
  cancelTask,
  closeTask,
  createTask,
  getParentCandidates,
  getTaskDetail,
  PRIORITY_LABELS,
  reopenTask,
  setParent,
  type TaskEventView,
  type TaskView,
} from './api'

/**
 * Everything about one task, in a dialog over the list.
 *
 * A dialog rather than a route because the list is the context: you open a
 * task, look, act, and are back where you were. A separate page would lose
 * scroll position and the filter you had set.
 *
 * The hierarchy is presented as links, not containment - closing a parent has
 * no effect on its children and vice versa, so showing children nested inside
 * a parent's card would imply an ownership that does not exist.
 */
export default function TaskDetail({ id, onClose, onOpenOther }: {
  id: string
  onClose: () => void
  onOpenOther: (id: string) => void
}) {
  const qc = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const { data, isPending } = useQuery({ queryKey: ['task', id], queryFn: () => getTaskDetail(id) })

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['task', id] })
    qc.invalidateQueries({ queryKey: ['tasks'] })
  }

  const handlers = {
    onSuccess: () => { setError(null); refresh() },
    onError: (e: Error) => setError(e.message),
  }

  const close = useMutation({ mutationFn: closeTask, ...handlers })
  const cancel = useMutation({ mutationFn: cancelTask, ...handlers })
  const reopen = useMutation({ mutationFn: reopenTask, ...handlers })
  const attach = useMutation({
    mutationFn: (parentId: string | null) => setParent(id, parentId),
    ...handlers,
  })
  const addChild = useMutation({
    mutationFn: (title: string) =>
      createTask({
        title,
        context: data!.task.context,
        priority: data!.task.priority,
        parentId: id,
      }),
    ...handlers,
  })

  // Escape closes, and the backdrop is clickable. Both are what people try.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="backdrop" onClick={onClose}>
      <div className="panel" role="dialog" aria-modal="true" onClick={e => e.stopPropagation()}>
        {isPending || !data ? (
          <p className="empty">Loading...</p>
        ) : (
          <>
            <div className="panel-head">
              <h2>{data.task.title}</h2>
              <button className="link" onClick={onClose} aria-label="Close">Close</button>
            </div>

            {error && <p className="error" role="alert">{error}</p>}

            <Facts task={data.task} />

            {data.task.description && <p className="desc">{data.task.description}</p>}

            <div className="panel-actions">
              {data.task.status === 'OPEN' ? (
                <>
                  <button onClick={() => close.mutate(id)}>Mark done</button>
                  <button className="link subtle" onClick={() => cancel.mutate(id)}>Cancel task</button>
                </>
              ) : (
                <button onClick={() => reopen.mutate(id)}>Reopen</button>
              )}
            </div>

            <ParentSection
              task={data.task}
              parent={data.parent}
              hasChildren={data.children.length > 0}
              onAttach={p => attach.mutate(p)}
              onOpenOther={onOpenOther}
            />

            <ChildrenSection
              children={data.children}
              canAdd={data.task.parentId === null}
              onAdd={title => addChild.mutate(title)}
              onOpenOther={onOpenOther}
            />

            <HistorySection events={data.events} />
          </>
        )}
      </div>
    </div>
  )
}

function Facts({ task }: { task: TaskView }) {
  return (
    <dl className="facts">
      <div><dt>Status</dt><dd>{task.status.toLowerCase()}</dd></div>
      <div><dt>Priority</dt><dd>{PRIORITY_LABELS[task.priority]}</dd></div>
      <div><dt>Context</dt><dd>{task.context === 'WORK' ? 'work' : 'personal'}</dd></div>
      <div><dt>Age</dt><dd>{ageInDays(task.createdAt)}d</dd></div>
      {task.dueAt && <div><dt>Due</dt><dd>{new Date(task.dueAt).toLocaleDateString()}</dd></div>}
      {task.tags.length > 0 && <div><dt>Tags</dt><dd>{task.tags.join(', ')}</dd></div>}
    </dl>
  )
}

function ParentSection({ task, parent, hasChildren, onAttach, onOpenOther }: {
  task: TaskView
  parent: TaskView | null
  hasChildren: boolean
  onAttach: (parentId: string | null) => void
  onOpenOther: (id: string) => void
}) {
  const [picking, setPicking] = useState(false)
  const { data: candidates } = useQuery({
    queryKey: ['parent-candidates', task.id],
    queryFn: () => getParentCandidates(task.id),
    enabled: picking,
  })

  return (
    <section className="panel-section">
      <h3>Parent</h3>

      {parent ? (
        <p className="relative">
          <button className="link" onClick={() => onOpenOther(parent.id)}>{parent.title}</button>
          <button className="link subtle" onClick={() => onAttach(null)}>Detach</button>
        </p>
      ) : hasChildren ? (
        <p className="muted-note">
          This task has subtasks of its own, so it cannot also be one. Maximum depth is two.
        </p>
      ) : picking ? (
        <select
          autoFocus
          defaultValue=""
          onChange={e => { if (e.target.value) { onAttach(e.target.value); setPicking(false) } }}
        >
          <option value="" disabled>Choose a parent...</option>
          {(candidates ?? []).map(c => <option key={c.id} value={c.id}>{c.title}</option>)}
        </select>
      ) : (
        <p className="relative">
          <span className="muted-note">None.</span>
          <button className="link" onClick={() => setPicking(true)}>Attach to a task</button>
        </p>
      )}
    </section>
  )
}

function ChildrenSection({ children, canAdd, onAdd, onOpenOther }: {
  children: TaskView[]
  canAdd: boolean
  onAdd: (title: string) => void
  onOpenOther: (id: string) => void
}) {
  const [title, setTitle] = useState('')
  const done = children.filter(c => c.status !== 'OPEN').length

  return (
    <section className="panel-section">
      <h3>
        Subtasks {children.length > 0 && <span className="muted-note">{done}/{children.length} done</span>}
      </h3>

      {children.length === 0 && <p className="muted-note">None yet.</p>}

      <ul className="plain">
        {children.map(child => (
          <li key={child.id}>
            <span className={`pri pri-${child.priority}`}>{PRIORITY_LABELS[child.priority]}</span>
            <button
              className={child.status === 'OPEN' ? 'link title-link' : 'link title-link done'}
              onClick={() => onOpenOther(child.id)}
            >
              {child.title}
            </button>
            {child.status !== 'OPEN' && (
              <span className="badge">{child.status.toLowerCase()}</span>
            )}
          </li>
        ))}
      </ul>

      {canAdd ? (
        <form
          className="subtask-add"
          onSubmit={e => {
            e.preventDefault()
            const trimmed = title.trim()
            if (!trimmed) return
            onAdd(trimmed)
            setTitle('')
          }}
        >
          <input
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="Add a subtask"
            aria-label="New subtask title"
          />
          <button type="submit" disabled={!title.trim()}>Add</button>
        </form>
      ) : (
        <p className="muted-note">
          This is already a subtask, so it cannot have subtasks of its own.
        </p>
      )}
    </section>
  )
}

const EVENT_LABELS: Record<string, string> = {
  CREATED: 'created',
  CLOSED: 'marked done',
  REOPENED: 'reopened',
  CANCELLED: 'cancelled',
  PRIORITY_CHANGED: 'priority changed',
  REPARENTED: 'parent changed',
  EDITED: 'edited',
}

function HistorySection({ events }: { events: TaskEventView[] }) {
  return (
    <section className="panel-section">
      <h3>History</h3>
      <ul className="plain timeline">
        {events.map((e, i) => (
          <li key={i}>
            <span className="when">
              {new Date(e.at).toLocaleString(undefined, {
                day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
              })}
            </span>
            <span>
              {EVENT_LABELS[e.type] ?? e.type.toLowerCase()}
              {e.type === 'PRIORITY_CHANGED' && e.fromValue && e.toValue &&
                ` (P${e.fromValue} to P${e.toValue})`}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
