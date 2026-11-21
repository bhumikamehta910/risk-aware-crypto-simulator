import { useId, useState } from 'react'
import { assets, names, sourceName, units, type Asset, type RequestApi } from './api'
import { useResource } from './useResource'

type Candle = {date:string;close:number}
type Series = {source:string;prices:Candle[]}
const dayMs = 86400000
const dateLabel = (day:string) => new Date(day+'T00:00:00Z').toLocaleDateString('en-US',{month:'short',day:'numeric',timeZone:'UTC'})

export function PriceChart({api, provider}: {api:RequestApi; provider?:string}) {
  const [asset,setAsset] = useState<Asset>('BTC')
  const [days,setDays] = useState(7)
  const [retry,setRetry] = useState(0)
  const [focus,setFocus] = useState<number | null>(null)
  // Refresh on a feed provider switch too. The response names the history's actual source.
  const resource = useResource<Series>(api,'market/history/'+asset+'?days='+days,(provider??'')+retry)
  const rows = resource.data?.prices ?? []
  const valid = rows.length===days && rows.every((r,i)=>Number.isFinite(r.close) && r.close>0 &&
    Number.isFinite(Date.parse(r.date)) && (!i || Date.parse(r.date)-Date.parse(rows[i-1].date)===dayMs))
  const id = useId()
  const min = Math.min(...rows.map(r=>r.close)), max = Math.max(...rows.map(r=>r.close))
  const pad = Math.max((max-min)*0.1,max*0.0001)
  const x = (i:number) => 12 + i*556/(days-1)
  const y = (v:number) => 8 + 128*(max+pad-v)/(max-min+2*pad)
  const path = rows.map((r,i)=>(i?'L':'M')+x(i)+' '+y(r.close)).join(' ')
  const selected = focus===null ? rows.at(-1) : rows[focus]
  const currency = resource.data?.source==='BINANCE_USDT'?'USDT':'USD'
  const error = resource.error || (resource.data && !valid ? 'Daily closes are missing or invalid for this period.' : '')
  return <section className="panel chart-panel" id="market"><div className="section-head"><h2>Price history</h2>
    <div className="seg">{[7,30].map(n=><button key={n} aria-pressed={n===days} onClick={()=>{setDays(n);setFocus(null)}}>{n}D</button>)}</div></div>
    <div className="chart-tools"><div className="asset-tabs">{assets.map(a=><button key={a} aria-pressed={a===asset} onClick={()=>{setAsset(a);setFocus(null)}}>{names[a]}</button>)}</div>
      {resource.data && <small>{sourceName(resource.data.source)}</small>}</div>
    {error ? <p className="warning" role="alert">{error} <button onClick={()=>setRetry(n=>n+1)}>Retry chart</button></p>
    : !valid || !selected ? <p className="placeholder">Loading daily closes…</p> : <>
      <div className="chart-readout"><strong>{units(selected.close)} <small>{currency}</small></strong><span>{dateLabel(selected.date)} · Daily close, UTC</span></div>
      <svg className="plot" viewBox="0 0 580 150" role="img" aria-label={asset+' '+days+' daily closes in '+currency}
        onPointerMove={e=>{const box=e.currentTarget.getBoundingClientRect();setFocus(Math.min(days-1,Math.max(0,Math.round(((e.clientX-box.left)/box.width*580-12)/556*(days-1)))))}} onPointerLeave={()=>setFocus(null)}>
        <defs><linearGradient id={id} x2="0" y2="1"><stop stopColor="#92cfa0" stopOpacity=".5"/><stop offset="1" stopColor="#92cfa0" stopOpacity=".02"/></linearGradient></defs>
        {[16,72,135].map(h=><path key={h} d={'M0 '+h+' H580'} stroke="#dbe5da" strokeDasharray="4 5"/>)}
        <path d={path+' L568 145 L12 145 Z'} fill={'url(#'+id+')'}/><path d={path} fill="none" stroke="#237853" strokeWidth="3"/>
        <circle cx={x(focus??days-1)} cy={y(selected.close)} r="4" fill="#165943"/>
      </svg><div className="axis"><span>{dateLabel(rows[0].date)}</span><span>{dateLabel(rows.at(-1)!.date)}</span></div>
      <details><summary>View daily closes</summary><ul>{rows.map(r=><li key={r.date}>{r.date} <b>{units(r.close)} {currency}</b></li>)}</ul></details>
    </>}
  </section>
}
