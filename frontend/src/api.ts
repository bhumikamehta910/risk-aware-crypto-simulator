export type Asset = 'BTC' | 'ETH'
export type Side = 'BUY' | 'SELL'
export type Profile = { id: string; email: string; virtualCashUsd: number }
export type Position = { asset: Asset; quantity: number; costUsd: number; averagePriceUsd: number }
export type Portfolio = { cashUsd: number; positions: Position[] }
export type Trade = { id: string; asset: Asset; side: Side; quantity: number; priceUsd: number;
  totalUsd: number; priceSource: string; executedAt: string }
export type RequestApi = <T>(path: string, options?: RequestInit) => Promise<T>
export const assets: Asset[] = ['BTC', 'ETH']
export const names = { BTC: 'Bitcoin', ETH: 'Ethereum' }
export const usd = (n: number) => n.toLocaleString('en-US', {style:'currency',currency:'USD'})
export const units = (n: number) => n.toLocaleString('en-US', {maximumFractionDigits:8})
export const exactPrice = (n: number) => n.toLocaleString('en-US', {minimumFractionDigits:2,maximumFractionDigits:12})
export const sourceName = (s: string) => ({BINANCE_USDT:'Binance · USDT',COINBASE_USD:'Coinbase · USD',MOCK_FIXED:'Fixed demo · USD'}[s] ?? s)
export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch('/api/' + path, options).catch(() => {
    throw new ApiError(0, 'Connection lost. Check your connection and try again.')
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.errors?.join('. ') || problem.detail ||
      ([502,503,504].includes(response.status) ? 'Service temporarily unavailable. If it is waking up, wait a minute and retry.' :
       response.status === 429 ? 'Too many requests. Wait a minute and try again.' :
       response.status === 401 ? 'Your session expired. Sign in again.' : 'Request failed. Please try again.'))
  }
  return response.json()
}
