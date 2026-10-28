<script setup lang="ts">
import {onErrorCaptured,ref} from 'vue'
const failed=ref(false)
const emit=defineEmits<{workspace:[mode:'rules']}>()
onErrorCaptured(()=>{failed.value=true;return false})
</script>
<template>
  <section v-if="failed" role="alert" style="padding:32px">
    <p>价格展示发生异常，暂时无法显示。您可以继续使用价格导入、发布和报价功能。</p>
    <button @click="failed=false">重新打开展示</button>
    <button @click="emit('workspace','rules')">返回运费规则列表</button>
  </section>
  <slot v-else />
</template>
