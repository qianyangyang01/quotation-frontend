<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ChannelGroup, { type ChannelTag } from './ChannelGroup.vue'

export type ProviderGroup = { provider: string; channels: ChannelTag[] }
export type CountryChannelOption = { country: string; stageLabel?: string; continent?: string; groups: ProviderGroup[] }

const props = defineProps<{
  attribute: string
  countries: CountryChannelOption[]
  preferredCountry?: string
  status: string
  updatedAt: string
}>()
const emit = defineEmits<{ maintain: []; remove: [] }>()
const expandedProviders = ref(false)
const moreOpen = ref(false)
const selectedCountry = ref(props.preferredCountry || props.countries[0]?.country || '')
watch(() => [props.preferredCountry, props.countries] as const, () => {
  const available = props.countries.some(item => item.country === selectedCountry.value)
  if (props.preferredCountry && props.countries.some(item => item.country === props.preferredCountry)) selectedCountry.value = props.preferredCountry
  else if (!available) selectedCountry.value = props.countries[0]?.country || ''
}, { deep: true })
const activeCountry = computed<CountryChannelOption>(() => props.countries.find(item => item.country === selectedCountry.value) || props.countries[0] || { country: '', groups: [] })
const groups = computed(() => activeCountry.value.groups)
const visibleGroups = computed(() => expandedProviders.value ? groups.value : groups.value.slice(0, 3))
const channelCount = computed(() => groups.value.reduce((total, group) => total + group.channels.length, 0))
</script>

<template>
  <article class="logistics-card">
    <section class="attribute-cell"><span>物流属性</span><strong>{{ attribute }}</strong></section>
    <section class="country-cell"><span>国家</span><select v-model="selectedCountry" aria-label="选择国家"><option v-for="item in countries" :key="item.country" :value="item.country">{{ item.stageLabel }}｜{{ item.country }}{{ item.stageLabel === '冷门国家' && item.continent ? `（${item.continent}）` : '' }}</option></select><small>{{ activeCountry.stageLabel || '一般国家' }}{{ activeCountry.stageLabel === '冷门国家' && activeCountry.continent ? ` · ${activeCountry.continent}` : '' }} · 共 {{ countries.length }} 个国家</small></section>
    <section class="channels-cell">
      <ChannelGroup v-for="group in visibleGroups" :key="group.provider" :provider="group.provider" :channels="group.channels" />
      <button v-if="groups.length > 3" class="provider-toggle" type="button" @click="expandedProviders=!expandedProviders">{{ expandedProviders ? '收起服务商 ↑' : `查看其余 ${groups.length - 3} 个服务商 ↓` }}</button>
    </section>
    <section class="match-cell"><span>匹配系数</span><strong>1个</strong><small>渠道 {{ channelCount }} ↑</small></section>
    <section class="status-cell"><span :class="{ disabled:status!=='启用' }">{{ status }}</span></section>
    <section class="updated-cell"><strong>{{ updatedAt.split(' ')[0] }}</strong><small>{{ updatedAt.split(' ')[1] || '' }}</small></section>
    <section class="actions-cell"><button class="maintain" type="button" @click="emit('maintain')">统一维护</button><button class="delete" type="button" @click="emit('remove')">删除</button><div class="more"><button type="button" aria-label="更多操作" @click="moreOpen=!moreOpen">•••</button><div v-if="moreOpen"><button type="button" @click="expandedProviders=true;moreOpen=false">展开全部服务商</button><button type="button" @click="expandedProviders=false;moreOpen=false">收起全部服务商</button></div></div></section>
  </article>
</template>

<style scoped>
.logistics-card{display:grid;grid-template-columns:11% 12% 11% minmax(0,29%) 8% 7% 10% minmax(150px,12%);align-items:start;padding:18px 16px;border:1px solid #e1e6ea;border-radius:8px;background:#fff;box-shadow:0 8px 24px rgba(25,39,51,.055);transition:.18s ease}.logistics-card:hover{border-color:#f0c584;box-shadow:0 12px 30px rgba(25,39,51,.085)}.logistics-card>section{min-width:0;padding:2px 12px}.attribute-cell,.country-cell{display:grid;align-content:start;gap:6px}.attribute-cell>span,.country-cell>span{display:none;color:#8a949c;font-size:9px}.attribute-cell strong{width:max-content;max-width:100%;padding:6px 10px;border-radius:12px;background:#fff0d8;color:#a65b00;font-size:12px}.country-cell select{width:100%;height:34px;padding:0 26px 0 9px;border:1px solid #d9e0e5;border-radius:7px;background:#fff;color:#27343e;font-size:11px;font-weight:800}.country-cell small{color:#909aa2;font-size:9px}.channels-cell{grid-column:3/5;padding-top:0!important}.provider-toggle{width:100%;margin-top:8px;padding:8px;border:1px dashed #f0bd73;border-radius:7px;background:#fffaf1;color:#a55d00;font-size:10px}.match-cell{display:grid;gap:6px}.match-cell span,.match-cell small{color:#8a949c;font-size:10px}.match-cell strong{font-size:17px}.status-cell span{display:inline-flex;padding:5px 10px;border-radius:999px;background:#e6f7ee;color:#168653;font-size:10px;font-weight:800}.status-cell span.disabled{background:#f0f2f4;color:#77828b}.updated-cell{display:grid;gap:4px;color:#45525c;font-size:11px}.updated-cell small{color:#919ba3}.actions-cell{display:flex;align-items:center;justify-content:flex-end;gap:5px;position:relative}.actions-cell>button{height:32px;padding:0 10px;border-radius:6px;font-size:10px;white-space:nowrap}.maintain{border:1px solid #ed8e00;background:#ff9900;color:#17232e;font-weight:800}.delete{border:0;background:none;color:#c44738}.more{position:relative}.more>button{width:32px;height:32px;border:1px solid #dbe1e5;border-radius:6px;background:#fff;color:#5c6872}.more>div{position:absolute;right:0;top:37px;z-index:10;width:145px;padding:5px;border:1px solid #dce2e6;border-radius:7px;background:#fff;box-shadow:0 10px 25px rgba(22,35,45,.16)}.more>div button{width:100%;padding:8px;border:0;border-radius:5px;background:#fff;text-align:left;font-size:10px}.more>div button:hover{background:#fff5e5;color:#9d5700}@media(max-width:1280px){.logistics-card{grid-template-columns:10% 12% 11% minmax(0,28%) 8% 7% 10% minmax(150px,14%);padding-inline:10px}.logistics-card>section{padding-inline:8px}.actions-cell{flex-wrap:wrap}}@media(max-width:900px){.logistics-card{grid-template-columns:1fr 1fr}.attribute-cell>span,.country-cell>span{display:block}.channels-cell{grid-column:1/-1;grid-row:2}.actions-cell{justify-content:flex-start}.match-cell,.status-cell,.updated-cell,.actions-cell{margin-top:10px}}@media(max-width:620px){.logistics-card{grid-template-columns:1fr}.channels-cell{grid-column:auto;grid-row:auto}.logistics-card>section{padding:6px 2px}}
</style>
