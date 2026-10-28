<script setup lang="ts">
import { onMounted } from 'vue'
import LogisticsPriceChanges from '@/components/logistics/LogisticsPriceChanges.vue'
import LogisticsPriceLoading from '@/components/logistics/LogisticsPriceLoading.vue'
import LogisticsPriceDisplayBoundary from '@/components/logistics/LogisticsPriceDisplayBoundary.vue'
import { logisticsPriceSession } from '@/data/logisticsPriceSession'
const props = withDefaults(defineProps<{ source?: 'preview' | 'system' }>(), { source: 'preview' })
const session = logisticsPriceSession(props.source)
const { snapshot, refreshing, error, checkedAt, filters } = session
const emit = defineEmits<{ workspace: [mode: 'base' | 'rules'] }>()
onMounted(() => { void session.ensureLoaded() })
</script>
<template>
  <div class="price-history-view">
    <LogisticsPriceDisplayBoundary @workspace="emit('workspace', $event)">
      <LogisticsPriceChanges v-if="snapshot" :state="filters" :snapshot="snapshot" :refreshing="refreshing" :error="error" :checked-at="checkedAt" @refresh="session.refresh()" @workspace="emit('workspace', $event)" />
      <LogisticsPriceLoading v-else :error="error" :loading="refreshing" @refresh="session.refresh()" @workspace="emit('workspace', $event)" />
    </LogisticsPriceDisplayBoundary>
  </div>
</template>
