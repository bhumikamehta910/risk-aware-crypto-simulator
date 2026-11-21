import { useCallback, useState } from 'react'
import { ApiError, assets, exactPrice, names, request, units, usd, type Asset, type Portfolio, type Profile, type RequestApi } from './api'
import { labels, useMarketPrices } from './MarketPrices'
import { OrderTicket } from './OrderTicket'
import { PriceChart } from './PriceChart'
import { TradeHistory } from './TradeHistory'
import { useResource } from './useResource'
import './dashboard.css'
import { RiskAnalysis } from './RiskAnalysis'

const signed = (n:number) => Math.abs(n) < 0.005 ? usd(0) : (n > 0 ? '+' : '') + usd(n)

export function Dashboard({profile,token,onExit}: {profile:Profile;token:string;onExit:(reason:string)=>void}) {
  const api: RequestApi = useCallback(async <T,>(path:string, options:RequestInit={}) => {
    const headers = new Headers(options.headers)
    headers.set('Authorization','Bearer '+token)
    try { return await request<T>(path,{...options,headers}) }
    catch (e) { if (e instanceof ApiError && e.status===401) onExit('Your session expired. Sign in again.'); throw e }
  },[token,onExit])
  const [revision,setRevision] = useState(0)
  const [limitsRevision,setLimitsRevision] = useState(0)
  const refresh = () => setRevision(n=>n+1)
  const {data:portfolio,error} = useResource<Portfolio>(api,'portfolio',revision)
  const {snapshot,connection} = useMarketPrices()
  const connected = connection==='Connected'
  function mark(asset:Asset) {
    const row = snapshot?.prices.find(p=>p.asset===asset)
    return connected && (row?.status==='LIVE'||row?.status==='MOCK_FIXED') ? row.quote?.priceUsd : undefined
  }
  const rows = portfolio?.positions.map(p=>{
    const price=mark(p.asset)
    const value=price===undefined?null:p.quantity*price
    return {...p,price,value,unrealized:value===null?null:value-p.costUsd}
  })
  const holdings = rows && rows.every(p=>p.value!==null) ? rows.reduce((sum,p)=>sum+p.value!,0) : null
  const total = portfolio && holdings!==null ? portfolio.cashUsd+holdings : null
  const pnl = total===null?null:total-10000
  const unrealized = rows && holdings!==null ? rows.reduce((sum,p)=>sum+p.unrealized!,0) : null
  const missing = !!rows?.some(p=>p.value===null)

  return <main className="dashboard"><header className="desk-header"><div className="brand"><span className="mark" aria-hidden="true">◈</span>
      <div><strong>Risk-Aware</strong><small>Crypto Trading Simulator</small></div></div>
      <nav aria-label="Dashboard sections"><a href="#overview">Overview</a><a href="#market">Market</a><a href="#risk">Risk</a><a href="#positions">Holdings</a><a href="#history">Activity</a></nav>
      <button className="signout" onClick={()=>onExit('You have signed out.')}>Sign out</button></header>
    <div className="desk-title" id="overview"><div><p className="eyebrow">PAPER TRADING ACCOUNT</p><h1>Portfolio overview</h1></div><span className="account">{profile.email}</span></div>
    {error && <p role="alert" className="warning">{error} <button onClick={refresh}>Retry account</button></p>}
    {missing && <p className="warning">Portfolio valuation is paused until fresh prices return for your holdings.</p>}
    <div className="summary-grid">
      <section className="net-value"><p>Total portfolio value</p><strong data-testid="total">{total===null?'—':usd(total)}</strong>
        <small>Virtual USD · Starting balance $10,000.00</small></section>
      <div className="metrics">{[
        ['Available cash',portfolio?usd(portfolio.cashUsd):'—','cash'],
        ['Holdings value',holdings===null?'—':usd(holdings),'holdings'],
        ['Total profit / loss',pnl===null?'—':signed(pnl),'pnl'],
        ['Unrealized profit / loss',unrealized===null?'—':signed(unrealized),'unrealized']
      ].map(([title,value,key])=><div key={key}><span>{title}</span><b data-testid={key} className={value.startsWith('-')?'loss':value.startsWith('+')?'gain':''}>{value}</b></div>)}</div>
    </div>
    <section className="quotes"><div className="section-head"><h2>Market prices</h2><p className="feed-status"><i className={connected?'online':''}/>{snapshot?.mode==='MOCK_FIXED'?'Mock mode':snapshot?.provider} · {connection}</p></div>
      <div className="quotes-grid">{assets.map(asset=>{
        const row=snapshot?.prices.find(p=>p.asset===asset)
        return <div className="quote" key={asset}><span aria-hidden="true" className={'coin '+asset}>{asset==='BTC'?'₿':'Ξ'}</span><div><b>{names[asset]}</b><small>{asset} · {row&&connected?labels[row.status]:connection}</small></div>
          <div className="quote-price"><b>{row?.quote?exactPrice(row.quote.priceUsd)+' '+(row.quote.source==='BINANCE_USDT'?'USDT':'USD'):'—'}</b>
          <small>{row?.quote?new Date(row.quote.priceTime).toLocaleString():'Awaiting quote'}</small></div></div>
      })}</div>{snapshot?.provider==='BINANCE' && <small className="convention">Binance settlement uses 1 USDT = 1 virtual USD.</small>}</section>
    <div className="desk-grid"><PriceChart api={api} provider={snapshot?.provider}/><OrderTicket api={api} owner={profile.id} portfolio={portfolio} snapshot={snapshot} connected={connected} refresh={refresh} limitsRevision={limitsRevision}/></div>
    <RiskAnalysis api={api} mode={snapshot?.mode} provider={snapshot?.provider} saved={()=>setLimitsRevision(n=>n+1)}
      value={portfolio && total!==null?{cash:portfolio.cashUsd,total,
        btc:rows?.find(p=>p.asset==='BTC')?.value??0,eth:rows?.find(p=>p.asset==='ETH')?.value??0}:null}/>
    <section id="positions" className="panel"><div className="section-head"><h2>Your holdings</h2><span className="muted">Moving-average cost</span></div>
      {!rows?<p className="placeholder">{error?'Holdings unavailable.':'Loading holdings…'}</p>:!rows.length?<p className="placeholder">You hold virtual cash only. Buy BTC or ETH to open a position.</p>:
      <div className="table-scroll"><table><thead><tr>{['Asset','Quantity','Average acquisition','Market price','Market value','Allocation¹','Unrealized P/L'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>
        {rows.map(p=><tr key={p.asset}><td><b>{names[p.asset]}</b> <small>{p.asset}</small></td><td>{units(p.quantity)}</td><td>${exactPrice(p.averagePriceUsd)}</td>
         <td>{p.price===undefined?'—':'$'+exactPrice(p.price)}</td><td>{p.value===null?'—':usd(p.value)}</td><td>{total&&p.value!==null?(100*p.value/total).toFixed(2)+'%':'—'}</td>
         <td className={p.unrealized!==null&&p.unrealized<0?'loss':'gain'}>{p.unrealized===null?'—':signed(p.unrealized)}</td></tr>)}</tbody></table></div>}
      {!!rows?.length && <small className="convention">¹ Share of total portfolio value, including cash.</small>}</section>
    <TradeHistory api={api} revision={revision}/>
    <footer>Virtual money only · Educational simulator · No financial advice</footer>
  </main>
}
