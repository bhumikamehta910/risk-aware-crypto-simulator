import { useEffect, useState } from 'react'

export type Row = {
  asset: 'BTC' | 'ETH'
  status: 'LIVE' | 'MOCK_FIXED' | 'STALE' | 'DISCONNECTED' | 'UNAVAILABLE'
  quote: { priceUsd: number; priceTime: string; source: string } | null
}
export type Snapshot = { mode: string; provider: string; prices: Row[] }

export const labels = {
  LIVE: 'Live', MOCK_FIXED: 'Fixed demo price', STALE: 'Stale price',
  DISCONNECTED: 'Feed disconnected', UNAVAILABLE: 'Waiting for price'
}

export function useMarketPrices() {
  const [snapshot, setSnapshot] = useState<Snapshot>()
  const [connection, setConnection] = useState('Connecting')
  useEffect(() => {
    let disposed = false
    let delay = 1000
    let retry: number
    let watchdog: number
    let socket: WebSocket
    function connect() {
      const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
      socket = new WebSocket(protocol + '//' + location.host + '/ws/prices')
      function resetTimeout() {
        window.clearTimeout(watchdog)
        watchdog = window.setTimeout(() => socket.close(), 20000)
      }
      resetTimeout()
      socket.onmessage = event => {
        if (disposed) return
        try {
          const next: Snapshot = JSON.parse(event.data)
          if (!Array.isArray(next.prices)) throw new Error('Invalid snapshot')
          setSnapshot(next)
          setConnection('Connected')
          delay = 1000
          resetTimeout()
        } catch { socket.close() }
      }
      socket.onclose = () => {
        window.clearTimeout(watchdog)
        if (disposed) return
        setConnection('Disconnected · reconnecting')
        retry = window.setTimeout(connect, delay)
        delay = Math.min(delay * 2, 30000)
      }
    }
    connect()
    return () => {
      disposed = true
      window.clearTimeout(retry)
      window.clearTimeout(watchdog)
      socket.close()
    }
  }, [])

  return { snapshot, connection }
}
