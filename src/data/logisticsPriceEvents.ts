/** Completion notifications only. No timers, price writes, or cache-invalidating side effects. */
export const LOGISTICS_PRICE_EVENT = 'milano:logistics-price-completed'
export type LogisticsPriceEvent = {id:string;kind:'imported'|'published'|'rollback'|'activated';at:number}
const sent=new Set<string>()
const valid=(value:unknown):value is LogisticsPriceEvent=>{
  const e=value as LogisticsPriceEvent | null
  return !!e&&typeof e.id==='string'&&['imported','published','rollback','activated'].includes(e.kind)&&Number.isFinite(e.at)
}
export function notifyLogisticsPriceChange(kind:LogisticsPriceEvent['kind'],operationId:string){
  if(typeof window==='undefined'||!operationId)return
  const id=kind+':'+operationId
  if(sent.has(id))return
  sent.add(id);if(sent.size>500)sent.delete(sent.values().next().value!)
  const event:LogisticsPriceEvent={id,kind,at:Date.now()}
  // Notification failure must never turn a successful publication into a reported failure.
  try{window.dispatchEvent(new CustomEvent(LOGISTICS_PRICE_EVENT,{detail:event}))}catch{/* display is optional */}
  try{localStorage.setItem(LOGISTICS_PRICE_EVENT,JSON.stringify(event))}catch{/* storage can be disabled */}
  try{const channel=new BroadcastChannel(LOGISTICS_PRICE_EVENT);channel.postMessage(event);channel.close()}catch{/* storage event is the fallback */}
}
export function subscribeLogisticsPriceChanges(receive:(event:LogisticsPriceEvent)=>void){
  const seen=new Set<string>()
  const deliver=(value:unknown)=>{if(valid(value)&&!seen.has(value.id)){
    seen.add(value.id);if(seen.size>500)seen.delete(seen.values().next().value!)
    try{receive(value)}catch{/* a display subscriber cannot break publication */}
  }}
  const local=(event:Event)=>deliver((event as CustomEvent).detail)
  const storage=(event:StorageEvent)=>{if(event.key===LOGISTICS_PRICE_EVENT&&event.newValue)try{deliver(JSON.parse(event.newValue))}catch{/* ignore invalid external storage */}}
  let channel:BroadcastChannel|undefined
  try{channel=new BroadcastChannel(LOGISTICS_PRICE_EVENT);channel.onmessage=event=>deliver(event.data)}catch{/* storage event is the fallback */}
  window.addEventListener(LOGISTICS_PRICE_EVENT,local);window.addEventListener('storage',storage)
  try{const saved=localStorage.getItem(LOGISTICS_PRICE_EVENT);if(saved)deliver(JSON.parse(saved))}catch{/* optional replay */}
  return()=>{channel?.close();window.removeEventListener(LOGISTICS_PRICE_EVENT,local);window.removeEventListener('storage',storage)}
}
