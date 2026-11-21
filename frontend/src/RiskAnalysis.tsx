import { useState, type FormEvent } from 'react'
import { assets, names, sourceName, usd, type Asset, type RequestApi } from './api'
import { useResource } from './useResource'

export type Exposure = {cash:number; btc:number; eth:number; total:number}
type Limits = {maxOrderUsd:number|null;btcPct:number;ethPct:number}
export type Assessment = {transactionUsd:number;quote:{priceTime:string;source:string;priceUsd:number};
  current:Exposure;projected:Exposure;limits:Limits;violations:string[]}
type Metrics = {asset:Asset;source:string;from:string;through:string;closes:number;returns:number;
  dailyVolatilityPct:number;drawdownPct:number;peakDate:string|null;troughDate:string|null}
const parts = ['btc','eth','cash'] as const
const pct = (amount:number,total:number) => total>0 ? amount*100/total : 0
const percent = (n:number) => n.toFixed(2)+'%'

function Allocation({current,projected}:{current:Exposure;projected?:Exposure}) {
  const view = projected ?? current
  const concentrated = assets.filter(a => view[a==='BTC'?'btc':'eth']>view.total/2)
  return <div className="risk-allocation">
    <div className="allocation-bar" aria-hidden="true">{parts.map(k=><span key={k} className={'slice-'+k}
      style={{width:pct(view[k],view.total)+'%'}}/>)}</div>
    <table><thead><tr><th>Asset</th><th>Current share</th>{projected && <th>After trade</th>}</tr></thead>
      <tbody>{parts.map(k=><tr key={k}><td><i className={'asset-key slice-'+k}/>{k.toUpperCase()}</td>
        <td>{percent(pct(current[k],current.total))}</td>{projected && <td>{percent(pct(projected[k],projected.total))}</td>}</tr>)}</tbody></table>
    {concentrated.map(a=><p className="warning" key={a}>{a} {projected?'would represent':'represents'} more than half of your portfolio. A fall in its price would have a large effect on your total value.</p>)}
  </div>
}

function Scenarios({value}:{value:Exposure}) {
  return <div className="scenario-table"><table><thead><tr><th>Price drop</th><th>BTC falls alone</th><th>ETH falls alone</th></tr></thead>
    <tbody>{[5,10,20].map(drop=><tr key={drop}><td>−{drop}%</td>{[value.btc,value.eth].map((exposure,i)=>{
      const loss=exposure*drop/100
      return <td key={i}><b>{loss>0?'−':''}{usd(loss)}</b><small>{usd(value.total-loss)} left</small></td>
    })}</tr>)}</tbody></table></div>
}

export function RiskPreview({preview,asset}:{preview:Assessment;asset:Asset}) {
  const {current,projected,limits}=preview
  return <section className="risk-preview" aria-label="Pre-trade risk preview"><h3>Pre-trade risk preview</h3>
    <p>Indicative snapshot · {sourceName(preview.quote.source)} · {new Date(preview.quote.priceTime).toLocaleTimeString()}</p>
    <dl><dt>Current portfolio</dt><dd>{usd(current.total)}</dd><dt>Proposed transaction</dt><dd>{usd(preview.transactionUsd)}</dd>
      <dt>Projected portfolio</dt><dd>{usd(projected.total)}</dd></dl>
    <Allocation current={current} projected={projected}/>
    <h4>Hypothetical losses after this order</h4><Scenarios value={projected}/>
    <p>Each drop affects one asset. Cash and the other price stay fixed. BTC and ETH can also fall together.</p>
    <p>Your maximum order is {limits.maxOrderUsd===null?'unlimited':usd(limits.maxOrderUsd)}. Your {asset} buy allocation cap is {percent(asset==='BTC'?limits.btcPct:limits.ethPct)}.</p>
    {preview.violations.map(v=><p role="alert" className="error" key={v}>Limit exceeded · {v}</p>)}
    <p>Confirmation checks current quotes, balances and limits again. Your receipt shows the final execution.</p>
  </section>
}

function Observations({api,provider}:{api:RequestApi;provider:string}) {
  const [asset,setAsset]=useState<Asset>('BTC')
  const [days,setDays]=useState(7)
  const [retry,setRetry]=useState(0)
  const {data,error}=useResource<Metrics>(api,'market/risk/'+asset+'?days='+days,provider+retry)
  return <><div className="risk-controls"><label>Asset <select aria-label="Historical risk asset" value={asset} onChange={e=>setAsset(e.target.value as Asset)}>
    {assets.map(a=><option key={a} value={a}>{names[a]}</option>)}</select></label>
    <label>Window <select aria-label="Historical risk window" value={days} onChange={e=>setDays(Number(e.target.value))}><option value="7">7 days</option><option value="30">30 days</option></select></label></div>
    {error?<p role="alert" className="warning">{error} <button onClick={()=>setRetry(n=>n+1)}>Retry observations</button></p>:!data?<p>Loading completed closes…</p>:<>
      <div className="risk-stats"><div><small title="Sample standard deviation of close-to-close percentage returns.">Daily volatility</small><strong>{percent(data.dailyVolatilityPct)}</strong></div>
        <div><small title="Largest decline from a closing peak to a later closing trough inside this window.">Maximum drawdown</small><strong>{percent(data.drawdownPct)}</strong></div></div>
      <p>{sourceName(data.source)} · {data.from} to {data.through} UTC<br/>{data.closes} daily closes · {data.returns} daily returns</p>
      <p>{data.peakDate?'Peak '+data.peakDate+' → trough '+data.troughDate:'No decline between closing prices in this window.'}</p></>}
    <details><summary>How to read these metrics</summary><p>Daily volatility measures how widely daily percentage moves varied around their average. It uses sample standard deviation and is reported per day. Maximum drawdown measures the largest observed decline from an earlier closing peak. Short windows and daily closes can miss larger losses, including within a day. Neither measure forecasts future prices.</p></details>
  </>
}

function LimitFields({api,initial,saved}:{api:RequestApi;initial:Limits;saved:()=>void}) {
  const [order,setOrder]=useState(initial.maxOrderUsd?.toString()??'')
  const [btc,setBtc]=useState(String(initial.btcPct))
  const [eth,setEth]=useState(String(initial.ethPct))
  const [busy,setBusy]=useState(false)
  const [note,setNote]=useState('')
  async function submit(e:FormEvent) {
    e.preventDefault();setBusy(true);setNote('')
    try {
      await api('risk/limits',{method:'PUT',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({maxOrderUsd:order||null,btcPct:btc,ethPct:eth})})
      setNote('Trading limits saved.');saved()
    } catch(e) {setNote(e instanceof Error?e.message:'Could not save limits.')}
    finally {setBusy(false)}
  }
  return <form className="limit-form" onSubmit={submit}>
    <label>Maximum order (USD)<input type="number" min="0.01" step=".01" placeholder="Unlimited" value={order} onChange={e=>setOrder(e.target.value)}/></label>
    <label>BTC allocation cap (%)<input required type="number" min="0" max="100" step=".01" value={btc} onChange={e=>setBtc(e.target.value)}/></label>
    <label>ETH allocation cap (%)<input required type="number" min="0" max="100" step=".01" value={eth} onChange={e=>setEth(e.target.value)}/></label>
    <button className="primary" disabled={busy}>{busy?'Saving…':'Save limits'}</button>
    {note && <p role="status">{note}</p>}</form>
}

export function RiskAnalysis({api,value,mode,provider,saved}:{api:RequestApi;value:Exposure|null;mode?:string;provider?:string;saved:()=>void}) {
  const [retry,setRetry]=useState(0)
  const limits=useResource<Limits>(api,'risk/limits',retry)
  return <section id="risk" className="panel risk-panel"><div className="section-head"><h2>Risk analysis</h2></div>
    <div className="risk-grid"><div><h3>Historical observations</h3>
      {mode==='LIVE'?<Observations api={api} provider={provider??''}/>:<p>{mode==='MOCK_FIXED'?'Fixed demo prices have no observed volatility or drawdown. Use LIVE mode for historical analysis.':'Waiting for market mode…'}</p>}</div>
      <div><h3>Current concentration</h3><p>Share of total value, including virtual cash. BTC and ETH can fall together.</p>
      {value?<Allocation current={value}/>:<p>Waiting for a complete portfolio valuation.</p>}</div>
      <div><h3>Hypothetical price drops</h3><p>Each scenario changes one price while other prices and quantities stay fixed. These are examples, not forecasts.</p>
      {value?<Scenarios value={value}/>:<p>Fresh prices are required for all holdings.</p>}</div>
    </div>
    <details className="limit-settings"><summary>Trading limits</summary><p>The order cap applies to buys and sells. An allocation cap limits purchases of that asset. You can sell down an existing position above its cap.</p>
      {limits.error?<p className="warning">{limits.error} <button onClick={()=>setRetry(n=>n+1)}>Retry limits</button></p>:limits.data?<LimitFields initial={limits.data} api={api} saved={saved}/>:<p>Loading limits…</p>}</details>
  </section>
}
