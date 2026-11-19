import { createApp, h, ref } from 'vue'
import LogisticsUpdateOverview from '../../src/components/logistics/LogisticsUpdateOverview.vue'
import { batchExportFixture } from '../../src/data/logisticsBatchExport.fixture'
const snapshot = batchExportFixture()
const app = { setup() {
  const open = ref(true)
  return () => h('div', [h('div', { style: 'padding:14px 24px;font:14px Microsoft YaHei;background:#fff8df;color:#7a5a18' }, '本地预览 · 样例数据 · 下载可用'), h('button', { onClick: () => { open.value = true }, style: 'margin:24px;padding:10px 20px' }, '价格更新概览'), open.value && h(LogisticsUpdateOverview, { snapshot, initialCountry: '全部国家', initialAttribute: '', initialWeight: 1, refreshing: false, error: '', onClose: () => { open.value = false } })])
} }
document.body.style.cssText = 'margin:0;background:#edf2f8'
createApp(app).mount('#app')
