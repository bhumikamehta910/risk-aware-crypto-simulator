import { useRef, useState, type FormEvent } from 'react'
import { ApiError, assets, exactPrice, names, sourceName, units, usd, type Asset, type Portfolio, type RequestApi, type Side, type Trade } from './api'
import type { Snapshot } from './MarketPrices'
import { RiskPreview, type Assessment } from './RiskAnalysis'

type Intent = { key: string; asset: Asset; side: Side; quantity: string }
function readIntent(storageKey: string): Intent | null {
  try {
    const item = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null')
    if (item && assets.includes(item.asset) && ['BUY','SELL'].includes(item.side) &&
        typeof item.quantity === 'string' && typeof item.key === 'string') return item
  } catch { /* Storage may be disabled. In-memory retries still use the same key. */ }
  return null
}

export function OrderTicket({api, owner, portfolio, snapshot, connected, refresh, limitsRevision}: {
  api: RequestApi; owner: string; portfolio?: Portfolio; snapshot?: Snapshot; connected: boolean; refresh: () => void; limitsRevision:number
}) {
  const storageKey = 'risk-aware.pending-trade.' + owner
  const [pending, setPending] = useState(() => readIntent(storageKey))
  const [asset, setAsset] = useState<Asset>(pending?.asset ?? 'BTC')
  const [side, setSide] = useState<Side>(pending?.side ?? 'BUY')
  const [quantity, setQuantity] = useState(pending?.quantity ?? '')
  const [assessment, setAssessment] = useState<{data:Assessment;version:number}>()
  const review = assessment?.version === limitsRevision ? assessment.data : undefined
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [error, setError] = useState('')
  const [receipt, setReceipt] = useState<Trade>()
  const row = snapshot?.prices.find(p => p.asset === asset)
  const fresh = connected && (row?.status === 'LIVE' || row?.status === 'MOCK_FIXED') &&
    portfolio?.positions.every(p=>snapshot?.prices.some(r=>r.asset===p.asset && (r.status==='LIVE'||r.status==='MOCK_FIXED')))
  const held = portfolio?.positions.find(p => p.asset === asset)?.quantity ?? 0
  const q = Number(quantity)
  const estimate = row?.quote && Number.isFinite(q) && q > 0 ? q * row.quote.priceUsd : null
  const frozen = busy || !!pending || !!review
  function validate() {
    if (!/^(?:\d{1,12}(?:\.\d{1,8})?|\.\d{1,8})$/.test(quantity) || !(q > 0)) return 'Enter a positive quantity with up to 8 decimal places.'
    if (!portfolio || !fresh || estimate === null) return 'Waiting for a current price and account balance.'
    if (estimate < 0.01) return 'The minimum order value is $0.01.'
    if (side === 'BUY' && estimate > portfolio.cashUsd) return 'Insufficient virtual cash for this purchase.'
    if (side === 'SELL' && q > held) return 'Insufficient ' + asset + ' holdings for this sale.'
    return ''
  }
  function remember(intent: Intent | null) {
    setPending(intent)
    try {
      if (intent) sessionStorage.setItem(storageKey, JSON.stringify(intent))
      else sessionStorage.removeItem(storageKey)
    } catch { /* Current tab memory remains available. */ }
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (lock.current) return
    setError('')
    if (!pending) {
      const reason = validate()
      if (reason) { setError(reason); setAssessment(undefined); return }
      if (!review) {
        setReceipt(undefined); lock.current=true; setBusy(true)
        try {
          const data=await api<Assessment>('trades/preview',{method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({asset,side,quantity})})
          setAssessment({data,version:limitsRevision})
        } catch(e) {setError(e instanceof Error?e.message:'Risk preview is unavailable.')}
        finally {lock.current=false;setBusy(false)}
        return
      }
      if (review.violations.length) return
    }
    lock.current = true
    setBusy(true)
    const intent = pending ?? {key:crypto.randomUUID(), asset, side, quantity}
    remember(intent)
    try {
      const trade = await api<Trade>('trades', {method:'POST', headers:{'Idempotency-Key':intent.key,'Content-Type':'application/json'},
        body:JSON.stringify({asset:intent.asset,side:intent.side,quantity:intent.quantity})})
      remember(null)
      setReceipt(trade)
      setAssessment(undefined)
      setQuantity('')
      refresh()
    } catch (cause) {
      if (cause instanceof ApiError && [400,409,422,503].includes(cause.status)) {
        // A gateway 503 can be ambiguous. Only the simulator's explicit price rejection is definitive.
        if (cause.status !== 503 || /price is (STALE|DISCONNECTED|UNAVAILABLE)/.test(cause.message)) remember(null)
        setError(cause.status===422?'Risk limit exceeded · '+cause.message:cause.message)
      } else setError('Execution could not be confirmed. Retry the same order to retrieve its result.')
      setAssessment(undefined)
    } finally { lock.current = false; setBusy(false) }
  }

  return <section id="order" className="panel ticket"><div className="section-head"><h2>Order ticket</h2><span className="tag">Virtual USD</span></div>
    <form onSubmit={submit}>
      <fieldset disabled={frozen}><legend>Side</legend><div className="seg side">{(['BUY','SELL'] as const).map(s =>
        <button type="button" key={s} aria-pressed={s===side} onClick={()=>{setSide(s);setError('');setReceipt(undefined)}}>{s==='BUY'?'Buy':'Sell'}</button>)}</div>
      <label htmlFor="asset">Asset</label><select id="asset" value={asset} onChange={e=>{setAsset(e.target.value as Asset);setError('');setReceipt(undefined)}}>
        {assets.map(a=><option key={a} value={a}>{names[a]} ({a})</option>)}</select>
      <label htmlFor="quantity">Quantity</label><div className="quantity-input"><input id="quantity" inputMode="decimal" autoComplete="off" placeholder="0.00000000"
        value={quantity} onChange={e=>{setQuantity(e.target.value);setError('');setReceipt(undefined)}}/><span>{asset}</span></div></fieldset>
      <div className="order-line"><span>Available {side==='BUY'?'cash':asset}</span><b>{portfolio ? side==='BUY'?usd(portfolio.cashUsd):units(held)+' '+asset : '—'}</b></div>
      <div className="order-line estimate"><span>Estimated amount</span><b>{estimate===null?'—':usd(estimate)}</b></div>
      {!fresh && <p className="warning">Price feed unavailable or stale. New orders are paused.</p>}
      {review && !pending && <RiskPreview preview={review} asset={asset}/>}
      {pending && !busy && <p className="warning">This order is awaiting confirmation. Retry it with its original request key.</p>}
      {error && <p role="alert" className="error">{error}</p>}
      <button className="primary" onClick={e=>{if(e.detail>1)e.preventDefault()}} disabled={busy || (!pending && (!fresh || !portfolio || !!review?.violations.length))}>
        {busy?(pending?'Submitting…':'Preparing preview…'):pending?'Retry same order':review?'Confirm simulated '+side.toLowerCase():'Review '+side.toLowerCase()} <span aria-hidden="true">→</span></button>
      {review && !pending && <button type="button" onClick={()=>setAssessment(undefined)}>Edit order</button>}
      {receipt && <p className="notice" role="status"><b>{receipt.side==='BUY'?'Bought':'Sold'} {units(receipt.quantity)} {receipt.asset}</b><br/>
        Executed at ${exactPrice(receipt.priceUsd)} · {usd(receipt.totalUsd)} total<br/>{sourceName(receipt.priceSource)}</p>}
    </form>
  </section>
}
