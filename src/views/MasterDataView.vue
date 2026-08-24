<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import AppTopbar from '@/components/AppTopbar.vue'
import {
  createSupplier, deleteSupplier, loadSuppliers, updateSupplier, type Supplier,
} from '@/services/masterData'

const rows = ref<Supplier[]>([])
const query = ref('')
const page = ref(0)
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const editor = ref<Supplier | null | undefined>(undefined)
const notice = ref('')
const form = reactive({ code: '', name: '', contactName: '', phone: '', platform: '', category: '', settlementTerms: '', leadTimeDays: '', rating: '', enabled: true })

let searchTimer = 0
watch(query, () => { window.clearTimeout(searchTimer); searchTimer = window.setTimeout(() => { page.value = 0; void reload() }, 300) })
onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const result = await loadSuppliers(query.value, page.value)
    rows.value = result.items; total.value = result.total; totalPages.value = result.totalPages
  } catch (error) { show(error instanceof Error ? error.message : '数据加载失败') }
  finally { loading.value = false }
}
function show(message: string) { notice.value = message; window.setTimeout(() => notice.value === message && (notice.value = ''), 3000) }
function open(row?: Supplier) {
  editor.value = row || null
  Object.assign(form, { code: row?.code || '', name: row?.name || '', contactName: row?.contactName || '', phone: row?.phone || '', platform: row?.platform || '', category: row?.category || '', settlementTerms: row?.settlementTerms || '', leadTimeDays: String(row?.leadTimeDays ?? ''), rating: String(row?.rating ?? ''), enabled: row?.enabled ?? true })
}
async function save() {
  if (!form.code.trim() || !form.name.trim()) return show('编码和名称不能为空')
  saving.value = true
  try {
    const input = { code: form.code, name: form.name, contactName: form.contactName, phone: form.phone, platform: form.platform, category: form.category, settlementTerms: form.settlementTerms, leadTimeDays: form.leadTimeDays === '' ? null : Number(form.leadTimeDays), rating: form.rating === '' ? null : Number(form.rating), enabled: form.enabled }
    if (editor.value) await updateSupplier(editor.value, input)
    else await createSupplier(input)
    editor.value = undefined; await reload(); show('保存成功')
  } catch (error) { show(error instanceof Error ? error.message : '保存失败') }
  finally { saving.value = false }
}
async function toggle(row: Supplier) {
  try {
    await updateSupplier(row, { ...row, enabled: !row.enabled })
    await reload(); show(row.enabled ? '已停用' : '已启用')
  } catch (error) { show(error instanceof Error ? error.message : '状态修改失败') }
}
async function remove(row: Supplier) {
  if (!window.confirm(`确认删除供应商“${row.name}”吗？有关联商品时系统会拒绝删除。`)) return
  try { await deleteSupplier(row.id); await reload(); show('供应商已删除') }
  catch (error) { show(error instanceof Error ? error.message : '删除失败') }
}
async function changePage(next: number) { page.value = next; await reload() }
</script>

<template>
  <AppTopbar />
  <main class="workspace">
    <header class="heading"><div><small>MASTER DATA</small><h1>供应商管理</h1><p>维护真实采购供应商及合作状态，关联商品后不可直接删除。</p></div><button @click="open()">＋ 新增供应商</button></header>
    <section class="summary"><article><small>供应商管理总数</small><b>{{ total }}</b></article><article><small>当前页启用</small><b>{{ rows.filter(row=>row.enabled).length }}</b></article></section>
    <section class="card"><div class="toolbar"><input v-model.trim="query" placeholder="搜索供应商名称或编码"><span>共 {{ total }} 条</span></div>
      <div v-if="loading" class="empty">正在加载…</div><div v-else-if="!rows.length" class="empty">暂无数据，请点击右上角新增。</div>
      <table v-else><thead><tr><th>名称 / 编码</th><th>联系人</th><th>平台 / 品类</th><th>结算 / 交期</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td><b>{{ row.name }}</b><small>{{ row.code }}</small></td><td><b>{{ row.contactName || '暂无' }}</b><small>{{ row.phone || '暂无' }}</small></td><td>{{ row.platform || '暂无' }} / {{ row.category || '暂无' }}</td><td>{{ row.settlementTerms || '暂无' }} / {{ row.leadTimeDays ?? '暂无' }} 天</td><td><em :class="{ enabled:row.enabled }">{{ row.enabled ? '启用' : '停用' }}</em></td><td class="actions"><button @click="open(row)">编辑</button><button @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</button><button class="danger" @click="remove(row)">删除</button></td></tr></tbody></table>
      <footer><button :disabled="page<=0" @click="changePage(page-1)">上一页</button><span>{{ page+1 }} / {{ Math.max(1,totalPages) }}</span><button :disabled="page+1>=totalPages" @click="changePage(page+1)">下一页</button></footer>
    </section>
  </main>
  <div v-if="editor !== undefined" class="mask" @click.self="editor=undefined"><section class="modal"><header><div><small>MASTER DATA EDITOR</small><h2>{{ editor ? '编辑' : '新增' }}供应商</h2></div><button @click="editor=undefined">×</button></header><div class="form"><label>编码*<input v-model.trim="form.code" maxlength="64"></label><label>名称*<input v-model.trim="form.name" maxlength="160"></label><label>联系人<input v-model.trim="form.contactName"></label><label>电话<input v-model.trim="form.phone"></label><label>采购平台<input v-model.trim="form.platform"></label><label>主营品类<input v-model.trim="form.category"></label><label>结算方式<input v-model.trim="form.settlementTerms"></label><label>平均交期（天）<input v-model="form.leadTimeDays" type="number" min="0"></label><label>评分（0-5）<input v-model="form.rating" type="number" min="0" max="5" step="0.1"></label><label><input v-model="form.enabled" type="checkbox"> 启用</label></div><footer><button @click="editor=undefined">取消</button><button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button></footer></section></div>
  <div v-if="notice" class="toast">{{ notice }}</div>
</template>

<style scoped>
.workspace{box-sizing:border-box;min-height:calc(100vh - 72px);padding:28px max(28px,calc((100vw - 1450px)/2));background:#f4f7f9;color:#172431}.heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:20px}.heading small,.modal small{color:#d87600;font-weight:900;letter-spacing:.15em}.heading h1{margin:7px 0;font-size:29px}.heading p{margin:0;color:#73808a}.heading>button,.primary{height:40px;padding:0 18px;border:0;border-radius:8px;background:#ff9810;font-weight:900}.summary{display:grid;grid-template-columns:repeat(2,minmax(180px,260px));gap:14px;margin-bottom:14px}.summary article{display:grid;gap:7px;padding:17px;border:1px solid #dde4e8;border-radius:10px;background:#fff}.summary small{color:#79858e}.summary b{font-size:25px}.card{overflow:hidden;border:1px solid #dde4e8;border-radius:10px;background:#fff}.toolbar{display:flex;align-items:center;gap:14px;padding:14px}.toolbar input{width:min(420px,70vw);height:38px;border:1px solid #d8e0e5;border-radius:7px;padding:0 11px}.toolbar span{margin-left:auto;color:#7b8790}table{width:100%;border-collapse:collapse}th,td{padding:13px 15px;border-top:1px solid #edf0f2;text-align:left;font-size:12px}th{background:#f7f9fa;color:#6c7a84}td b,td small{display:block}td small{margin-top:4px;color:#8a959d}td em{padding:4px 8px;border-radius:10px;background:#f0f2f3;color:#78838b;font-style:normal}td em.enabled{background:#e6f6ec;color:#16834d}.actions{white-space:nowrap}.actions button{border:0;background:none;color:#a85f00;font-weight:800}.actions .danger{color:#c33f37}.card>footer{display:flex;justify-content:flex-end;align-items:center;gap:12px;padding:13px}.card>footer button{height:32px;border:1px solid #dbe2e6;border-radius:6px;background:#fff}.empty{padding:70px;text-align:center;color:#7d8992}.mask{position:fixed;z-index:80;inset:0;display:grid;place-items:center;padding:25px;background:rgba(15,25,35,.5)}.modal{width:min(720px,94vw);padding:22px;border-radius:12px;background:#fff}.modal>header{display:flex;justify-content:space-between}.modal h2{margin:5px 0}.modal>header button{border:0;background:none;font-size:24px}.form{display:grid;grid-template-columns:1fr 1fr;gap:13px;margin-top:18px}.form label{display:grid;gap:6px;font-size:11px}.form .wide{grid-column:1/-1}.form input,.form textarea{box-sizing:border-box;width:100%;min-height:38px;border:1px solid #d9e1e6;border-radius:6px;padding:8px}.form textarea{min-height:70px}.modal>footer{display:flex;justify-content:flex-end;gap:10px;margin-top:20px}.modal>footer button{height:40px;padding:0 18px;border:1px solid #d8e0e5;border-radius:7px;background:#fff}.modal>footer .primary{border:0;background:#ff9810}.toast{position:fixed;right:25px;bottom:25px;z-index:100;padding:13px 18px;border-radius:8px;background:#172431;color:#fff}@media(max-width:800px){.heading{align-items:start;flex-direction:column;gap:14px}.form{grid-template-columns:1fr}.form .wide{grid-column:auto}.card{overflow:auto}table{min-width:800px}}
</style>
