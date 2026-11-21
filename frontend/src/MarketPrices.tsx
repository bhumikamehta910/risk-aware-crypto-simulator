import { useEffect, useState } from 'react'

type Row = {
  asset: 'BTC' | 'ETH'
  status: 'LIVE' | 'MOCK_FIXED' | 'STALE' | 'DISCONNECTED' | 'UNAVAILABLE'
  quote: { priceUsd: number; priceTime: string; source: string } | null
}
type Snapshot = { mode: string; provider: string; prices: Row[] }

const labels = {
  LIVE: 'Live', MOCK_FIXED: 'Fixed demo price', STALE: 'Stale price',
  DISCONNECTED: 'Feed disconnected', UNAVAILABLE: 'Waiting for price'
}

export function MarketPrices() {
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

  return <section className="market-panel" aria-label="Market prices">
    <h2>Market prices</h2>
    <p className="muted">{snapshot ? (snapshot.mode === 'LIVE' ? 'Live mode' : 'Offline demonstration') + ' · ' + snapshot.provider + ' · ' : ''}{connection}</p>
    <div className="market-grid">{snapshot?.prices.map(({asset, quote, status}) =>
      <div key={asset}><h3>{asset === 'BTC' ? 'Bitcoin' : 'Ethereum'} <small>{asset}</small></h3>
        <strong>{quote ? quote.priceUsd.toLocaleString(undefined, {maximumFractionDigits: 8}) : '—'} {quote && (quote.source === 'BINANCE_USDT' ? 'USDT' : 'USD')}</strong>
        <p>{connection === 'Connected' ? labels[status] : 'Last received price'}</p>
        {quote && <time dateTime={quote.priceTime}>{new Date(quote.priceTime).toLocaleString()}</time>}
      </div>)}</div>
  </section>
}
