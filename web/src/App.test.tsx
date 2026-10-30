import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App, type TopologyResponse } from './App'

const sample: TopologyResponse = {
  generatedAt: '2026-10-22T10:00:05+09:00',
  sources: {
    docker: { status: 'OK' },
    prometheus: { status: 'ERROR', message: '아직 연결하지 않았습니다' },
    host: { status: 'STALE' },
  },
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('App', () => {
  it('출처 세 개의 상태를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(sample), { status: 200 })))

    render(<App />)

    expect(await screen.findByText('docker: OK')).toBeTruthy()
    expect(screen.getByText('prometheus: ERROR')).toBeTruthy()
    expect(screen.getByText('host: STALE')).toBeTruthy()
  })

  it('API 요청이 실패하면 연결 끊김을 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    render(<App />)

    expect(await screen.findByText('연결 끊김')).toBeTruthy()
  })
})
