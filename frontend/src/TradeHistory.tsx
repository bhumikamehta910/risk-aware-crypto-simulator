import { useState } from 'react'
import { assets, exactPrice, sourceName, units, usd, type RequestApi, type Trade } from './api'
import { useResource } from './useResource'

export function TradeHistory({api,revision}: {api:RequestApi;revision:number}) {
  const [asset,setAsset] = useState('')
  const [side,setSide] = useState('')
  const [page,setPage] = useState(0)
  const [refresh,setRefresh] = useState(0)
  const params = new URLSearchParams({page:String(page),size:'5'})
  if (asset) params.set('asset',asset)
  if (side) params.set('side',side)
  const {data,error} = useResource<{trades:Trade[];hasNext:boolean}>(api,'trades?'+params,revision+refresh)
  return <section id="history" className="panel"><div className="section-head"><h2>Trade history</h2>
    <div className="filters"><select aria-label="Filter history by asset" value={asset} onChange={e=>{setAsset(e.target.value);setPage(0)}}>
      <option value="">All assets</option>{assets.map(a=><option key={a}>{a}</option>)}</select>
      <select aria-label="Filter history by side" value={side} onChange={e=>{setSide(e.target.value);setPage(0)}}>
      <option value="">Both sides</option><option>BUY</option><option>SELL</option></select></div></div>
    {error?<p role="alert" className="warning">{error} <button onClick={()=>setRefresh(n=>n+1)}>Retry history</button></p>
     : !data?<p className="placeholder">Loading executions…</p>:!data.trades.length?<p className="placeholder">{asset||side?'No trades match these filters.':'Your completed trades will appear here.'}</p>:
    <div className="table-scroll"><table><thead><tr>{['Executed','Asset','Side','Quantity','Unit price','Amount','Source'].map(h=><th key={h}>{h}</th>)}</tr></thead>
      <tbody>{data.trades.map(t=><tr key={t.id}><td>{new Date(t.executedAt).toLocaleString()}</td><td>{t.asset}</td><td><span className={'trade-side '+t.side}>{t.side}</span></td>
      <td>{units(t.quantity)}</td><td>${exactPrice(t.priceUsd)}</td><td>{usd(t.totalUsd)}</td><td>{sourceName(t.priceSource)}</td></tr>)}</tbody></table></div>}
    <div className="pager"><small>Page {page+1}</small><button disabled={!page} onClick={()=>setPage(p=>p-1)}>Previous</button>
    <button disabled={!data?.hasNext} onClick={()=>setPage(p=>p+1)}>Next</button></div>
  </section>
}
