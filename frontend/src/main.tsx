import { useEffect, useState, type FormEvent } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

type Profile = { id: string; email: string; virtualCashUsd: number }
type Session = { accessToken: string; expiresInSeconds: number }

async function request<T>(path: string, options: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch('/api/' + path, options)
  } catch {
    throw new Error('Connection lost. Check that the simulator is running and try again.')
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new Error(problem.errors?.join('. ') || problem.detail ||
      (response.status === 401 ? 'Please sign in again.' : 'The account service is unavailable. Try again.'))
  }
  return response.json()
}

function App() {
  const [mode, setMode] = useState<'register' | 'login'>('register')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [expires, setExpires] = useState(0)

  useEffect(() => {
    if (!expires) return
    const timer = window.setTimeout(() => {
      setProfile(null)
      setNotice('Your session expired. Sign in to continue.')
      setExpires(0)
    }, Math.max(0, expires - Date.now()))
    return () => window.clearTimeout(timer)
  }, [expires])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setNotice('')
    setError('')
    try {
      const options = { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }) }
      if (mode === 'register') {
        if (new TextEncoder().encode(password).length > 72) {
          throw new Error('Your password exceeds 72 UTF-8 bytes. Shorten it and try again.')
        }
        await request<Profile>('auth/register', options)
        setMode('login')
        setNotice('Account created. Sign in to view your virtual balance.')
      } else {
        const session = await request<Session>('auth/login', options)
        const account = await request<Profile>('me', {
          headers: { Authorization: 'Bearer ' + session.accessToken }
        })
        setProfile(account)
        setExpires(Date.now() + session.expiresInSeconds * 1000)
      }
      setPassword('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to reach your account.')
    } finally {
      setBusy(false)
    }
  }

  const registering = mode === 'register'
  return <main>
    <header className="flex items-center gap-3"><span className="mark" aria-hidden="true">◈</span>
      <div><strong>Risk-Aware</strong><small>Crypto Trading Simulator</small></div>
    </header>
    <section className="workspace">
      <aside><div className="eyebrow">PAPER TRADING ACCOUNT</div>
        <h1>Practice with<br/><em>virtual capital.</em></h1>
        <div className="art" aria-hidden="true"><div className="coin-stack"><i/><i/><i/><i/><i/><i/></div></div>
        <div className="funds"><small>Opening virtual balance</small><b>$10,000<span>.00</span></b><span>USD</span></div>
      </aside>
      <article>{profile ? <>
        <p className="eyebrow">YOUR ACCOUNT</p><h2>Account overview</h2>
        <p className="muted break-all">{profile.email}</p>
        <dl><dt>Available virtual cash</dt><dd>{profile.virtualCashUsd.toLocaleString('en-US', {style:'currency',currency:'USD'})}</dd>
        <dt>Account ID</dt><dd className="identifier">{profile.id}</dd></dl>
        <button onClick={() => {setProfile(null); setExpires(0); setNotice('You have signed out.')}}>Sign out</button>
      </> : <>
        <div className="switcher">{(['register', 'login'] as const).map(item =>
          <button key={item} type="button" aria-pressed={mode === item} disabled={busy}
            onClick={() => {setMode(item); setNotice(''); setError(''); setPassword('')}}>
            {item === 'register' ? 'Create account' : 'Sign in'}</button>)}</div>
        <h2>{registering ? 'Create your account' : 'Welcome back'}</h2>
        <p className="muted">{registering ? 'Start with $10,000 in virtual USD.' : 'Sign in to your simulator account.'}</p>
        <form onSubmit={submit}>
          <label htmlFor="email">Email address</label><input id="email" autoComplete="email" type="email" required maxLength={254} placeholder="you@example.com" value={email} onChange={e => setEmail(e.target.value)}/>
          <label htmlFor="password">Password</label><input id="password" type="password" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={12} maxLength={registering ? undefined : 72} value={password} onChange={e => setPassword(e.target.value)}/>
          {registering && <small className="muted">Use at least 12 characters.</small>}
          {error && <p role="alert" className="error">{error}</p>}
          {notice && <p role="status" className="notice">{notice}</p>}
          <button disabled={busy} className="action">{busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}<span aria-hidden="true">→</span></button>
        </form>
      </>}</article>
    </section>
    <footer>Virtual money only <span>·</span> Educational simulator <span>·</span> No financial advice</footer>
  </main>
}

createRoot(document.getElementById('root')!).render(<App/>)
