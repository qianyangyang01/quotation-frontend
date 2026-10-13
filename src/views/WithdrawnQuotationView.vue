<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import QuotationSystemView from './QuotationSystemView.vue'

const route = useRoute()
const sourceId = computed(() => typeof route.query.record === 'string' ? route.query.record : '__missing__')
</script>

<template>
  <QuotationSystemView :key="sourceId" :resume-quote-id="sourceId" />
</template>
