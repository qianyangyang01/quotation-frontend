<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { updateQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { loadRecord } from '@/data/quotationRecordQuery'
import { recordCustomerPrices, priceComparison, priceComparisonLabel, type CustomerPriceSnapshot } from '@/data/customerQuotePrices'
import { quoteSheetUsd } from '@/data/customerQuoteSheet'
import CustomerPriceRevision from './CustomerPriceRevision.vue'

const props=defineProps<{ record:QuotationRecord; canEdit:boolean }>()
const emit=defineEmits<{ saved:[record:QuotationRecord] }>()
const draft=ref<CustomerPriceSnapshot>(recordCustomerPrices(props.record))
const editing=ref(false), saving=ref(false), error=ref(''), filter=ref(''), conflict=ref(false)
const inputs=ref<Record<string,string>>({})
const key=(id:string,q:number)=>JSON.stringify([id,q])
function reset() {
  draft.value=recordCustomerPrices(props.record); editing.value=false; error.value=''; conflict.value=false
  inputs.value=Object.fromEntries(draft.value.rows.flatMap(row=>draft.value.quantities.map((q,i)=>[key(row.optionId,q),row.prices[i]==null?'':row.prices[i]!.toFixed(2)])))
}
watch(()=>[props.record.id,props.record._version,props.record.updatedAt],()=>{filter.value='';reset()})
reset()
const lines=computed(()=>priceComparison(props.record,draft.value))
const visible=computed(()=>lines.value.filter(line=>!filter.value || line.option.id===filter.value))
const usd=quoteSheetUsd
const signed=(v:number|null,suffix='')=>v==null?'—':`${v>0?'+':''}${v.toFixed(2)}${suffix}`
function restore(id:string,q:number,price:number|null) { inputs.value[key(id,q)]=price==null?'':price.toFixed(2) }
function buildDraft() {
  return { quantities:[...draft.value.quantities], rows:draft.value.rows.map(row=>({optionId:row.optionId,prices:draft.value.quantities.map(q=>{
    const text=inputs.value[key(row.optionId,q)].trim()
    if (!text) return null
    if (!/^\d+(?:\.\d{1,2})?$/.test(text) || Number(text)>999999999.99) throw new Error('客户价格须为非负美元金额，最多两位小数；留空表示未报价')
    return Number(text)
  })})) }
}
async function save(confirm = false, markWon = false) {
  if (saving.value || !props.canEdit || (confirm && (editing.value || props.record.quoteConfirmed)) || (markWon && (editing.value || props.record.status==='won'))) return
  const id=props.record.id, version=props.record._version
  error.value='';saving.value=true
  try {
    const updated=await updateQuotationRecord(id,markWon ? {status:'won'} : confirm ? {quoteConfirmed:true} : {customerQuote:buildDraft()},version)
    if (props.record.id!==id) return
    if (!updated) throw new Error('保存失败，请重试')
    if (markWon && updated.status!=='won') throw new Error('标记成交未生效，请刷新后重试')
    if (confirm && !updated.quoteConfirmed) throw new Error('确认报价未生效，请刷新后重试')
    if ((updated._version ?? -1)<(props.record._version ?? -1)) return
    emit('saved',updated); editing.value=false
  } catch(e) {
    if (props.record.id!==id) return
    error.value=e instanceof Error?e.message:'保存失败，请重试'
    conflict.value=(e as {status?:number})?.status===409
  } finally {saving.value=false}
}
async function reload() {
  if (saving.value) return
  const id=props.record.id; saving.value=true
  try { const row=await loadRecord(id);if (row && props.record.id===id && (row._version ?? -1)>=(props.record._version ?? -1)) emit('saved',row) }
  catch(e) {error.value=e instanceof Error?e.message:'加载失败'} finally {saving.value=false}
}
const history=computed(()=>props.record.revisions.filter(item=>item.field==='customerQuote').slice().reverse())
</script>
<template>
  <section class="customer-price-comparison">
    <header><div><h3>系统报价与客户报价对比</h3><p>系统原价保留；记录未改价时沿用报价单，改价后以记录最后保存为准。</p></div><b>{{ priceComparisonLabel(record) }}</b></header>
    <div class="price-controls"><label>国家 / 区域 / 渠道 <select v-model="filter"><option value="">全部渠道</option><option v-for="option in record.quoteOptions" :key="option.id" :value="option.id">{{ option.country }} · {{ option.quoteRegion || '' }} · {{ option.carrier }} · {{ option.channel }}</option></select></label><span>USD · 同渠道、同数量整单价</span></div>
    <p v-if="!record.customerQuote && !record.sheetQuote" class="price-note">历史记录未保存客户改价，暂按系统报价显示。</p>
    <p v-if="error" role="alert">{{ error }} <button v-if="conflict" type="button" :disabled="saving" @click="reload">重新加载记录（放弃未保存修改）</button></p>
    <div class="price-table-scroll"><table><thead><tr><th>国家 / 渠道</th><th>数量</th><th>系统报价</th><th>最终客户报价</th><th>较系统差额</th><th>调整幅度</th></tr></thead><tbody>
      <tr v-for="line in visible" :key="key(line.option.id,line.quantity)" :class="{changed:line.changed}">
        <td><b>{{ line.option.country }} · {{ line.option.carrier }}</b><small>{{ line.option.quoteRegion }} · {{ line.option.channel }}</small></td>
        <td>{{ line.quantity || '自定义' }}{{ line.quantity ? record.quoteMode==='bundle'?'套':'件' : '' }}</td><td>{{ usd(line.system) }}</td>
        <td class="customer-price"><template v-if="editing"><input v-model="inputs[key(line.option.id,line.quantity)]" :aria-label="`${line.option.id} ${line.quantity}客户报价`" :disabled="saving" inputmode="decimal" maxlength="15" placeholder="未报价"><button type="button" :disabled="saving" @click="restore(line.option.id,line.quantity,line.system)">恢复系统价</button></template><template v-else><b>{{ usd(line.customer) }}</b><small>{{ line.recordEdited?'记录修改':line.changed?'报价单修改':'与系统一致' }}</small></template></td>
        <td>{{ line.system==null ? '无系统基准' : line.customer==null ? '未报价' : signed(line.difference) }}</td><td>{{ signed(line.percent,'%') }}</td>
      </tr>
    </tbody></table></div>
    <p class="price-note">{{ editing?'正在编辑，保存后更新对比与复制结果。':'只计算金额差异；名称、时效修改不计入改价。' }}</p>
    <footer v-if="canEdit"><template v-if="editing"><button type="button" :disabled="saving" @click="reset">取消</button><button class="primary" type="button" :disabled="saving" @click="save()">{{ saving?'正在保存…':'保存客户报价' }}</button></template><template v-else><button class="mark-won" type="button" :disabled="saving || record.status==='won'" @click="save(false,true)">{{ record.status==='won' ? '已成交' : '标记已成交' }}</button><button type="button" :disabled="saving || record.quoteConfirmed" @click="save(true)">{{ record.quoteConfirmed ? '已处理' : '确认报价' }}</button><button class="primary" type="button" :disabled="saving" @click="editing=true">编辑客户报价</button></template></footer>
    <p v-if="record.quoteConfirmed" class="confirmation-note">已确认报价 · {{ record.quoteConfirmedBy }} · {{ record.quoteConfirmedAt ? new Date(record.quoteConfirmedAt).toLocaleString('zh-CN') : '' }}</p>
    <p v-else class="confirmation-note">核对后点击“确认报价”标记已处理；再次改价需重新确认。</p>
    <details v-if="history.length"><summary>客户报价修改记录 · {{ history.length }} 次</summary><article v-for="entry in history" :key="entry.id"><b>{{ entry.editorName }} · {{ new Date(entry.changedAt).toLocaleString('zh-CN') }}</b><CustomerPriceRevision :record="record" :before="entry.before" :after="entry.after" /></article></details>
  </section>
</template>
<style scoped>
.customer-price-comparison footer .mark-won{color:#178653;border-color:#a9dfbd;background:#f0faf4}.customer-price-comparison footer{flex-wrap:wrap}
.customer-price-comparison{background:white;border:1px solid #e0e5e9;border-radius:9px;padding:18px;color:#202532}.customer-price-comparison header{display:flex;justify-content:space-between;gap:16px;align-items:center}.customer-price-comparison h3{margin:0;font-size:18px}.customer-price-comparison p,.price-controls span{font-size:12px;line-height:1.6;color:#71808c}.customer-price-comparison header>b{color:#bd6418;font-size:12px}.price-controls{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:14px 0;font-size:12px}.price-controls select{max-width:420px;width:100%;margin-top:5px;padding:8px;border:1px solid #d9dfe4;border-radius:5px}.price-table-scroll{overflow:auto;max-height:55vh}.customer-price-comparison table{width:100%;border-collapse:collapse;font-size:13px}.customer-price-comparison th{background:#fff1e4;position:sticky;top:0}.customer-price-comparison td,.customer-price-comparison th{padding:13px 10px;border:1px solid #e2e6ea;text-align:center;white-space:nowrap}.customer-price-comparison td:first-child{text-align:left}.customer-price-comparison small{display:block;font-size:11px;color:#7b8790;margin-top:5px}.changed .customer-price{background:#fff7ec;color:#d5701b}.customer-price input{width:90px;padding:8px;border:1px solid #f58220;border-radius:5px}.customer-price button{display:block;border:0;padding:3px;margin:auto;color:#c86f1f;background:transparent}.customer-price-comparison footer{display:flex;gap:10px;justify-content:flex-end;margin-top:14px}.customer-price-comparison button{cursor:pointer}.customer-price-comparison footer button{padding:9px 16px;border:1px solid #ddd;border-radius:6px;background:white}.customer-price-comparison footer .primary{background:#f58220;color:#fff;border-color:#f58220}.customer-price-comparison button:disabled{opacity:.5;cursor:wait}.customer-price-comparison [role=alert]{background:#fff0e8;color:#b34f24;padding:12px}.customer-price-comparison details{margin-top:16px;font-size:12px}.customer-price-comparison details p{overflow-wrap:anywhere}.customer-price-comparison details article{border-bottom:1px solid #eee;padding:10px 0}
</style>
