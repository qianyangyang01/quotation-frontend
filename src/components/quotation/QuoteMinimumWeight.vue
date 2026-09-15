<script setup lang="ts">
import { displayWeightGrams } from '@/services/quotationDecimal'
defineProps<{ weightKg?: number }>()
</script>

<template>
  <small v-if="weightKg && weightKg > 0" class="minimum-weight">整票最低计费 {{ displayWeightGrams(weightKg) }}g，不足自动补重</small>
</template>
