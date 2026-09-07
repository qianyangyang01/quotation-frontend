const base = "http://127.0.0.1:18098";
class Session {
  constructor(account) { this.account = account; this.cookies = new Map() }
  async request(path, method = 'GET', body) {
    const headers = { Accept: 'application/json', Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ') }
    if (body) headers['Content-Type'] = 'application/json'
    if (this.csrf && method !== 'GET') headers[this.csrf.headerName] = this.csrf.token
    if (method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID()
    const response = await fetch(base + '/api/v1' + path, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(20000) })
    for (const cookie of response.headers.getSetCookie()) { const pair=cookie.split(';')[0],i=pair.indexOf('='); this.cookies.set(pair.slice(0,i),pair.slice(i+1)) }
    const value = await response.json()
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${value.message}`)
    return value.data
  }
  async login() { this.csrf = await this.request('/auth/csrf'); await this.request('/auth/login','POST',{ account: this.account, password: process.env.PERF_PASSWORD || 'PerfAdmin123!' }) }
}

export { Session };
