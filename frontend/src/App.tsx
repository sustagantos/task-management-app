import { useQuery } from '@tanstack/react-query'

type Me = { id: string; email: string; displayName: string }

/**
 * M0 placeholder. It exists to prove the full auth path works before any task
 * feature is written: Entra sign-in, session cookie, user provisioning, and the
 * owner id that every later query will be scoped by.
 *
 * A 401 here means the session is gone, so we send the browser to Spring's
 * OAuth2 entry point rather than trying to renew from JavaScript.
 */
async function fetchMe(): Promise<Me> {
  const res = await fetch('/api/me', { credentials: 'same-origin' })
  if (res.status === 401) {
    window.location.href = '/oauth2/authorization/entra'
    throw new Error('unauthenticated')
  }
  if (!res.ok) throw new Error(`GET /api/me failed: ${res.status}`)
  return res.json()
}

/** Reads the CSRF cookie Spring set. Not HttpOnly, by design - see SecurityConfig. */
function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function signOut() {
  await fetch('/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
  })
  window.location.href = '/'
}

export default function App() {
  const { data, isPending, error } = useQuery({ queryKey: ['me'], queryFn: fetchMe })

  if (isPending) return <main style={styles.main}>Loading…</main>
  if (error) return <main style={styles.main}>Could not load your account: {error.message}</main>

  return (
    <main style={styles.main}>
      <h1 style={styles.h1}>Tasks</h1>
      <p>
        Signed in as <strong>{data.displayName}</strong> ({data.email})
      </p>
      <p style={styles.muted}>Owner id {data.id}</p>
      <p style={styles.muted}>
        M0 scaffold. Task capture, the closed-today list and the analytics land in M1 onward.
      </p>
      <button type="button" onClick={signOut}>
        Sign out
      </button>
    </main>
  )
}

const styles = {
  main: {
    fontFamily: 'system-ui, -apple-system, Segoe UI, sans-serif',
    maxWidth: '42rem',
    margin: '4rem auto',
    padding: '0 1rem',
    lineHeight: 1.5,
  },
  h1: { fontSize: '1.5rem', marginBottom: '1rem' },
  muted: { color: '#666', fontSize: '0.9rem' },
} as const
