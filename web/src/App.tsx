import { useEffect, useState } from 'react'

type SourceStatus = 'OK' | 'STALE' | 'ERROR'

interface SourceState {
  status: SourceStatus
  snapshotAt?: string | null
  message?: string | null
}

export interface TopologyResponse {
  generatedAt: string
  sources: { docker: SourceState; prometheus: SourceState; host: SourceState }
}

const SOURCE_NAMES = ['docker', 'prometheus', 'host'] as const

export function App() {
  const [topology, setTopology] = useState<TopologyResponse | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetch('/api/topology')
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        return response.json() as Promise<TopologyResponse>
      })
      .then(setTopology)
      .catch(() => setFailed(true))
  }, [])

  return (
    <main>
      <h1>homeview</h1>
      {failed && <p>연결 끊김</p>}
      {topology && (
        <ul>
          {SOURCE_NAMES.map((name) => (
            <li key={name}>{`${name}: ${topology.sources[name].status}`}</li>
          ))}
        </ul>
      )}
    </main>
  )
}
