import { useEffect, useState } from 'react'
import type { RequestApi } from './api'

// Tag results with their request so filters and post-trade refreshes never display an older response.
export function useResource<T>(api: RequestApi, path: string, revision: string | number = 0): {data?:T;error?:string} {
  const key = path + '#' + revision
  const [result, setResult] = useState<{key:string; data?:T; error?:string}>()
  useEffect(() => {
    const controller = new AbortController()
    api<T>(path, {signal: controller.signal}).then(data => {
      if (!controller.signal.aborted) setResult({key,data})
    }).catch(error => {
      if (!controller.signal.aborted) setResult({key,error: error.message})
    })
    return () => controller.abort()
  }, [api,path,key])
  return result?.key === key ? result : {}
}
