import { lazy, Suspense, useEffect, useState } from 'react'
import MainPage from './MainPage'
import History from './History'

// Recharts is 420 kB of the bundle. Loading it on the main page - the one
// opened dozens of times a day - to render charts that are not on screen is
// the wrong trade. These two routes fetch it on first visit instead.
const Analytics = lazy(() => import('./Analytics'))
const Review = lazy(() => import('./Review'))
import './app.css'

/**
 * Hash routing, deliberately without a router library.
 *
 * Two views do not justify react-router's bundle or its concepts. A hash keeps
 * the back button and bookmarking working, and this is trivially replaceable
 * the moment a third view needs nested state or route params.
 */
function useHashRoute(): string {
  const [hash, setHash] = useState(() => window.location.hash)

  useEffect(() => {
    const onChange = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  return hash.replace('#', '').replace('/', '').split('?')[0]
}

export default function App() {
  const route = useHashRoute()
  switch (route) {
    case 'history':
      return <History />
    case 'analytics':
      return <Suspense fallback={<main className="page">Loading charts...</main>}><Analytics /></Suspense>
    case 'review':
      return <Suspense fallback={<main className="page">Loading...</main>}><Review /></Suspense>
    default:
      return <MainPage />
  }
}
