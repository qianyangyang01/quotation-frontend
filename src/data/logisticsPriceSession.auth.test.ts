// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {clearLogisticsPriceSessions,logisticsPriceSession} from './logisticsPriceSession'
import {authState,initializeAuth,login,logout} from './authStore'
const http=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),request:vi.fn()}))
vi.mock('@/services/http',()=>({api:{get:http.get,post:http.post},request:http.request,setRequestAccount:vi.fn(),resetCsrf:vi.fn()}))
vi.mock('@/services/financeSettings',()=>({clearFinanceSettingsCache:vi.fn()}))
vi.mock('@/data/publishedLogisticsRepository',()=>({clearPublishedLogisticsCache:vi.fn()}))
const user={id:'a',account:'A',name:'A',role:'logistics',permissions:['logistics'],mustChangePassword:false}
beforeEach(()=>{vi.clearAllMocks();clearLogisticsPriceSessions();authState.current=null;authState.permissions=[];authState.initialized=false;localStorage.clear();http.request.mockResolvedValue({capturedAt:'2026-10-28T01:00:00Z',sourceRevision:'r1',channels:[],versions:[]})})
afterEach(clearLogisticsPriceSessions)
it('logging in again as the same user clears the previous login snapshot and filters',async()=>{
 http.post.mockResolvedValue(user);await login('A','fixture')
 const old=logisticsPriceSession('system');await old.ensureLoaded();old.filters.country='新西兰'
 await login('A','fixture')
 expect(old.snapshot.value).toBeNull();expect(logisticsPriceSession('system').filters.country).toBe('德国')
})
it('normal auth verification preserves data but a permission change clears it',async()=>{
 http.get.mockResolvedValue(user);await initializeAuth(true)
 const old=logisticsPriceSession('system');await old.ensureLoaded()
 await initializeAuth(true);expect(logisticsPriceSession('system')).toBe(old);expect(old.snapshot.value).not.toBeNull()
 http.get.mockResolvedValue({...user,permissions:[]});await initializeAuth(true)
 expect(old.snapshot.value).toBeNull();expect(logisticsPriceSession('system')).not.toBe(old)
})
it('logout clears data even when the logout request fails',async()=>{
 const old=logisticsPriceSession('system');await old.ensureLoaded()
 http.post.mockRejectedValue(new Error('offline'));await expect(logout()).rejects.toThrow('offline')
 expect(old.snapshot.value).toBeNull();expect(logisticsPriceSession('system')).not.toBe(old)
})
