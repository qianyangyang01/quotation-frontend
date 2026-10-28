import { defineAsyncComponent } from 'vue'
import LogisticsPriceLoading from './LogisticsPriceLoading.vue'

// Keep the resolved component across workspace remounts as well as tab switches.
export const PriceChangesView = defineAsyncComponent({
  loader: () => import('@/views/LogisticsPriceChangesLocalView.vue'),
  loadingComponent: LogisticsPriceLoading,
  delay: 0,
})
