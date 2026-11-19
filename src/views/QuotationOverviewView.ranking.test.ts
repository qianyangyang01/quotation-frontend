// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationOverviewView.vue'
const mock=vi.hoisted(()=>({ranking:vi.fn()}))
vi.mock('@/services/quotationAnalyticsRecords',()=>({loadAnalyticsRecords:()=>new Promise(()=>{})}))
vi.mock('@/services/quotationAnalyticsPurchases',()=>({loadAnalyticsPurchases:()=>new Promise(()=>{})}))
vi.mock('@/services/quotationChannelRanking',async importOriginal=>({...await importOriginal<typeof import('@/services/quotationChannelRanking')>(),loadChannelRanking:mock.ranking}))
let app:App,root:HTMLDivElement
afterEach(()=>{app?.unmount();root?.remove();vi.useRealTimers()})
it('renders the independent aggregate while existing overview catalogs are still pending',async()=>{
 vi.useFakeTimers()
 mock.ranking.mockResolvedValue({items:[],totalUses:0,totalGroups:0,pendingGroups:0,unmatchedGroups:0,topFiveShare:0,total:0,page:0,size:5,totalPages:0,generatedAt:'2026-11-19T02:00:00Z',startDate:'2026-10-21',endDate:'2026-11-19'})
 root=document.createElement('div');document.body.append(root);app=createApp(View);app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(root)
 await vi.advanceTimersByTimeAsync(310);await nextTick()
 expect(mock.ranking).toHaveBeenCalledTimes(1)
 expect(root.querySelector('.channel-ranking')?.textContent).toContain('暂无有效渠道使用记录')
 expect(root.querySelector('.kpis')).toBeNull()
 expect((root.querySelector('.export')as HTMLButtonElement).disabled).toBe(true)
})
