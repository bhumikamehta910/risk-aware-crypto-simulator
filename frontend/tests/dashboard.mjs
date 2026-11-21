// Run against a local instance. Each run registers a new disposable paper account.
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'

const url = process.env.DASHBOARD_URL || 'http://127.0.0.1:5173'
const mode = process.env.DASHBOARD_TEST_MODE || 'MOCK_FIXED'
const browser = await chromium.launch({channel:'msedge'})
try {
  const page = await browser.newPage({viewport:{width:1360,height:960}})
  const errors = []
  page.on('pageerror', e=>errors.push(e.message))
  let relay, original
  await page.routeWebSocket('**/ws/prices', route=>{
    relay = route
    const server=route.connectToServer()
    server.onMessage(message=>{
      original=JSON.parse(message.toString())
      route.send(message)
    })
  })
  const credentials = {email:'dashboard-'+crypto.randomUUID()+'@example.test',password:'local-browser-test-passphrase'}
  assert.equal((await page.request.post(url+'/api/auth/register',{data:credentials})).status(),201)
  await page.goto(url)
  await page.getByRole('button',{name:'Sign in',exact:true}).click()
  await page.getByLabel('Email address').fill(credentials.email)
  await page.getByLabel('Password',{exact:true}).fill(credentials.password)
  await page.locator('form button').click()
  await page.getByRole('heading',{name:'Portfolio overview'}).waitFor()
  const ticket = page.locator('#order')
  const button = name=>ticket.getByRole('button',{name,exact:true})
  async function metric(key,value) {
    await page.waitForFunction(([k,v])=>document.querySelector('[data-testid="'+k+'"]')?.textContent===v,[key,value])
  }
  await metric('cash','$10,000.00')
  await page.locator('#market summary').waitFor({timeout:45000})
  await page.locator('.feed-status').filter({hasText:/· Connected/}).waitFor({timeout:45000})
  assert.equal(original.mode,mode)
  async function review(asset,side,quantity) {
    await ticket.getByLabel('Asset',{exact:true}).selectOption(asset)
    await button(side==='buy'?'Buy':'Sell').click()
    await ticket.getByLabel('Quantity').fill(quantity)
    await button('Review '+side+'').click()
  }
  async function order(asset,side,quantity) {
    await review(asset,side,quantity)
    const completed=page.waitForResponse(r=>r.url().endsWith('/api/trades')&&r.request().method()==='POST')
    await button('Confirm simulated '+side+'').click()
    const response=await completed
    assert.equal(response.status(),200)
    const receipt=await response.json()
    await ticket.getByRole('status').waitFor()
    assert.ok((await ticket.getByRole('status').innerText()).includes(receipt.priceUsd.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:12})))
    return receipt
  }
  if (mode==='LIVE') {
    const risk = page.locator('#risk')
    await risk.getByText(/7 daily closes · 6 daily returns/).waitFor({timeout:45000})
    await risk.getByLabel('Historical risk asset').selectOption('ETH')
    await risk.getByLabel('Historical risk window').selectOption('30')
    await risk.getByText(/30 daily closes · 29 daily returns/).waitFor({timeout:45000})
    await review('BTC','buy','0.001')
    await ticket.getByRole('heading',{name:'Pre-trade risk preview'}).waitFor()
    assert.match(await ticket.locator('.risk-preview').innerText(),/Indicative snapshot/)
    await ticket.getByRole('button',{name:'Edit order'}).click()
    const receipt=await order('BTC','buy','0.001')
    assert.notEqual(receipt.priceSource,'MOCK_FIXED')
    await page.locator('#positions tbody tr').first().waitFor()
    if (process.env.DASHBOARD_SCREENSHOT) await page.screenshot({path:process.env.DASHBOARD_SCREENSHOT,fullPage:true})
    console.log('Verified live browser purchase and history from '+receipt.priceSource)
  } else {
    const risk=page.locator('#risk')
    await risk.getByText(/Fixed demo prices have no observed/).waitFor()
    await risk.getByText('Trading limits',{exact:true}).click()
    await risk.getByLabel('BTC allocation cap (%)').fill('5')
    await risk.getByRole('button',{name:'Save limits'}).click()
    await risk.getByText('Trading limits saved.').waitFor()
    await review('BTC','buy','0.02')
    const assessment=ticket.locator('.risk-preview')
    await assessment.getByText(/Limit exceeded/).waitFor()
    assert.ok(await button('Confirm simulated buy').isDisabled())
    assert.match(await assessment.locator('.risk-allocation tbody tr').first().innerText(),/0.00%\s+10.00%/)
    assert.match(await assessment.locator('.scenario-table tbody tr').last().innerText(),/200.00[\s\S]*9,800.00/)
    if (process.env.RISK_PREVIEW_SCREENSHOT) await ticket.screenshot({path:process.env.RISK_PREVIEW_SCREENSHOT})
    await metric('cash','$10,000.00')
    await risk.getByLabel('BTC allocation cap (%)').fill('100')
    await risk.getByRole('button',{name:'Save limits'}).click()
    await button('Review buy').waitFor()
    await risk.getByText('Trading limits',{exact:true}).click()
    // Simulate a lost HTTP response after the server has already committed.
    let lostKey
    await page.route('**/api/trades',async route=>{
      if (route.request().method()!=='POST') return route.continue()
      const key=route.request().headers()['idempotency-key']
      if (!lostKey) {
        lostKey=key
        assert.equal((await route.fetch()).status(),200)
        await route.abort('failed')
      } else {
        assert.equal(key,lostKey)
        await route.continue()
      }
    })
    await review('BTC','buy','0.02')
    await button('Confirm simulated buy').dblclick()
    await button('Retry same order').click()
    await metric('cash','$9,000.00')
    await page.unroute('**/api/trades')
    await page.waitForFunction(()=>document.querySelectorAll('#history tbody tr').length===1)
    await order('BTC','sell','0.01')
    await metric('cash','$9,500.00')
    await order('ETH','buy','1')
    await metric('cash','$7,000.00')
    await order('ETH','sell','0.4')
    await metric('cash','$8,000.00')
    await metric('holdings','$2,000.00')
    const moved=structuredClone(original)
    for (const row of moved.prices) row.quote.priceUsd=row.asset==='BTC'?52000:3000
    relay.send(JSON.stringify(moved))
    await metric('total','$10,320.00')
    await metric('pnl','+$320.00')
    await metric('unrealized','+$320.00')
    for (const row of moved.prices) row.status='STALE'
    relay.send(JSON.stringify(moved))
    await metric('total','—')
    assert.ok(await button('Review sell').isDisabled())
    relay.send(JSON.stringify(original))
    await review('ETH','sell','2')
    assert.match(await ticket.getByRole('alert').innerText(),/Insufficient ETH/)
    await review('BTC','buy','2')
    assert.match(await ticket.getByRole('alert').innerText(),/Insufficient virtual cash/)
    await review('BTC','buy','0.000000001')
    assert.match(await ticket.getByRole('alert').innerText(),/8 decimal places/)
    await page.getByLabel('Filter history by asset').selectOption('ETH')
    await page.getByLabel('Filter history by side').selectOption('SELL')
    await page.waitForFunction(()=>document.querySelectorAll('#history tbody tr').length===1)
    assert.match(await page.locator('#history tbody').innerText(),/ETH[\s\S]*SELL/)
    await page.locator('#market').getByRole('button',{name:'Ethereum'}).click()
    await page.getByRole('button',{name:'30D'}).click()
    await page.getByLabel('ETH 30 daily closes in USD').waitFor()
    await page.locator('#market summary').click()
    assert.equal(await page.locator('#market li').count(),30)
    await page.locator('#market summary').click()
    await ticket.getByLabel('Quantity').fill('')
    if (process.env.DASHBOARD_SCREENSHOT) await page.screenshot({path:process.env.DASHBOARD_SCREENSHOT,fullPage:true})
    await order('ETH','sell','0.6')
    await metric('cash','$9,500.00')
    await order('ETH','buy','0.010001')
    await metric('cash','$9,474.99')
    await order('ETH','sell','0.010001')
    await metric('pnl','-$0.01')
    await metric('unrealized','$0.00')
    await page.getByLabel('Filter history by asset').selectOption('')
    await page.getByLabel('Filter history by side').selectOption('')
    await page.getByRole('button',{name:'Next',exact:true}).click()
    await page.getByText('Page 2',{exact:true}).waitFor()
    await page.waitForFunction(()=>document.querySelectorAll('#history tbody tr').length===2)
    await page.route('**/api/market/history/BTC?**',route=>route.fulfill({
      json:{source:'MOCK_FIXED',prices:[{date:'2026-01-02',close:50000}]}
    }))
    await page.locator('#market').getByRole('button',{name:'Bitcoin'}).click()
    await page.getByText(/Daily closes are missing or invalid/).waitFor()
    await page.unroute('**/api/market/history/BTC?**')
    await page.getByRole('button',{name:'Retry chart'}).click()
    await page.getByLabel('BTC 30 daily closes in USD').waitFor()
    await page.setViewportSize({width:375,height:812})
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))
    await page.route('**/api/trades?**',r=>r.fulfill({status:401,body:'{}',contentType:'application/json'}))
    await page.getByLabel('Filter history by side').selectOption('BUY')
    await page.getByText('Your session expired. Sign in again.').waitFor()
    console.log('Verified both assets and sides, lost-response retry, duplicates, balances, P/L, stale quotes, validation, history, charts, mobile width and 401 sign-in.')
  }
  assert.deepEqual(errors,[])
} finally {await browser.close()}
