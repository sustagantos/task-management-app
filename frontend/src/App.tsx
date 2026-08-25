import { useEffect, useState } from 'react'
import MainPage from './MainPage'
import History from './History'
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
  return route === 'history' ? <History /> : <MainPage />
}
