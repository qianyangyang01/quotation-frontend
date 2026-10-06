import { computed, onUnmounted, ref, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { listPersonalCustomers, addPersonalCustomer, renamePersonalCustomer, removePersonalCustomer, usePersonalCustomer, type PersonalQuotationCustomer } from '@/data/personalQuotationCustomers'

export function usePersonalQuotationCustomers() {
  const rows=ref<PersonalQuotationCustomer[]>([]), loading=ref(false), busy=ref(false), error=ref('')
  const account=computed(()=>currentAuthUser.value.id+':'+currentAuthUser.value.account)
  let generation=0, reads=0, disposed=false
  const valid=(version:number)=>!disposed && version===generation
  watch(account,()=>{generation++;reads++;rows.value=[];loading.value=false;busy.value=false;error.value=''})
  onUnmounted(()=>{disposed=true;generation++;reads++})
  function sort() { rows.value.sort((a,b)=>(b.lastUsedAt||'').localeCompare(a.lastUsedAt||'')) }
  async function load() {
    if(!currentAuthUser.value.id || busy.value)return
    const version=generation, read=++reads;loading.value=true;error.value=''
    try { const result=await listPersonalCustomers();if(valid(version)&&read===reads)rows.value=result }
    catch(e){if(valid(version)&&read===reads)error.value=e instanceof Error?e.message:'个人客户加载失败，请重试'}
    finally {if(valid(version)&&read===reads)loading.value=false}
  }
  async function mutate<T>(work:()=>Promise<T>,apply:(result:T)=>void):Promise<T|undefined> {
    if(!currentAuthUser.value.id||busy.value)return
    const version=generation;reads++;loading.value=false;busy.value=true;error.value=''
    try {const result=await work();if(!valid(version))return;apply(result);return result}
    catch(e){if(valid(version))error.value=e instanceof Error?e.message:'操作失败，请重试'}
    finally{if(valid(version))busy.value=false}
  }
  function upsert(row:PersonalQuotationCustomer){rows.value=[row,...rows.value.filter(item=>item.id!==row.id)];sort()}
  return {rows,loading,busy,error,load,
    add:(name:string,key:string)=>mutate(()=>addPersonalCustomer(name,key),upsert),
    rename:(row:PersonalQuotationCustomer,name:string)=>mutate(()=>renamePersonalCustomer(row,name),upsert),
    remove:(row:PersonalQuotationCustomer)=>mutate(async()=>{await removePersonalCustomer(row);return true},()=>{rows.value=rows.value.filter(item=>item.id!==row.id)}),
    markUsed:(row:PersonalQuotationCustomer)=>mutate(()=>usePersonalCustomer(row),upsert),
  }
}
