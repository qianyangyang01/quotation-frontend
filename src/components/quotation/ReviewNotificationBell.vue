<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { currentAuthUser, hasAnyPermission } from '@/data/authStore'
import { reviewNotifications as inbox, startReviewNotifications, stopReviewNotifications, refreshReviewNotifications, openReviewInbox, markAllReviewsRead, reviewMessageTitle, type ReviewMessage } from '@/data/reviewNotifications'

const router = useRouter(), root = ref<HTMLElement>()
const enabled = computed(() => !!currentAuthUser.value.account && hasAnyPermission('myRecords', 'allRecords'))
watch(() => enabled.value ? currentAuthUser.value.account : '', account => startReviewNotifications(account), { immediate: true })
function toggle() { if (inbox.opened) inbox.opened = false; else openReviewInbox() }
function outside(event: PointerEvent) { if (root.value && !root.value.contains(event.target as Node)) inbox.opened = false }
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && inbox.opened) { inbox.opened = false; root.value?.querySelector<HTMLButtonElement>('.notification-trigger')?.focus() } }
function wake() { void refreshReviewNotifications() }
async function view(item: ReviewMessage) {
  inbox.opened = false
  await router.push({ path: '/quotation/my-records', query: { record: item.recordId, reviewEvent: item.eventId, reviewOpen: crypto.randomUUID() } })
}
const time = (at: string) => new Date(at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape); document.addEventListener('visibilitychange', wake); window.addEventListener('focus', wake); window.addEventListener('online', wake) })
onUnmounted(() => { stopReviewNotifications(); document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); document.removeEventListener('visibilitychange', wake); window.removeEventListener('focus', wake); window.removeEventListener('online', wake) })
</script>

<template>
  <div v-if="enabled" ref="root" class="review-notification-bell">
    <button class="notification-trigger" type="button" :aria-label="`审核消息，${inbox.total} 条未读${inbox.error ? '，同步异常' : ''}`" :aria-expanded="inbox.opened" aria-controls="review-inbox" @click="toggle">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4M12 1v1" /></svg>
      <b v-if="inbox.total">{{ inbox.total > 99 ? '99+' : inbox.total }}</b><i v-else-if="inbox.error">!</i>
    </button>
    <section v-if="inbox.opened" id="review-inbox" class="review-inbox" aria-label="审核消息">
      <header><strong>审核消息</strong><span>未读 <b>{{ inbox.total }}</b></span><button aria-label="关闭审核消息" @click="inbox.opened = false">×</button></header>
      <div v-if="inbox.total || inbox.markingAll" class="inbox-bulk-actions"><small>无需逐条打开报价</small><button type="button" :disabled="inbox.markingAll || !inbox.total" :aria-busy="inbox.markingAll" @click="markAllReviewsRead">{{ inbox.markingAll ? '正在标记…' : '全部标为已读' }}</button></div>
      <p v-if="inbox.error" class="notification-error" role="alert">{{ inbox.error }} <button @click="refreshReviewNotifications()">重试</button></p>
      <p v-if="inbox.loading && !inbox.items.length" role="status">正在加载审核消息…</p>
      <p v-else-if="!inbox.total && !inbox.error" class="inbox-empty">暂无未读审核消息</p>
      <div class="inbox-messages">
        <article v-for="item in inbox.items" :key="item.eventId">
          <i :class="{ rejected: item.status === 'rejected', comment: item.kind === 'comment' }" />
          <div><b>{{ reviewMessageTitle(item) }}</b><span>{{ item.customerName || '未填写客户' }} · {{ item.primarySku || item.quoteNo }}</span><p v-if="item.note">审核意见：{{ item.note }}</p><small>{{ item.actorName }} · {{ time(item.occurredAt) }}</small></div>
          <button @click="view(item)">查看报价</button>
        </article>
      </div>
      <nav v-if="inbox.totalPages > 1" aria-label="审核消息分页"><button :disabled="inbox.loading || !inbox.page" @click="refreshReviewNotifications(inbox.page - 1)">上一页</button><span>{{ inbox.page + 1 }} / {{ inbox.totalPages }}</span><button :disabled="inbox.loading || inbox.page + 1 >= inbox.totalPages" @click="refreshReviewNotifications(inbox.page + 1)">下一页</button></nav>
      <footer>可逐条查看报价，也可一键将全部提醒标为已读</footer>
    </section>
    <aside v-if="inbox.announcement && !inbox.opened" class="review-arrival" role="status"><button @click="openReviewInbox">{{ inbox.announcement }} · 查看</button><button aria-label="关闭审核提示" @click="inbox.announcement = ''">×</button></aside>
  </div>
</template>

<style scoped>
.inbox-bulk-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 16px;border-bottom:1px solid #edf0f3;background:#fffbf2}.inbox-bulk-actions small{font-size:11px;color:#82909a}.review-inbox .inbox-bulk-actions button{padding:7px 10px;border:1px solid #e9bf81;border-radius:6px;background:#fff;color:#a45b00;white-space:nowrap}
.review-notification-bell{position:relative;margin-left:auto;padding-left:16px;color:#172431}.notification-trigger{position:relative;display:grid;place-items:center;width:42px;height:42px;background:#fff;border:0;border-radius:8px;color:#52616d;cursor:pointer}.notification-trigger:hover{background:#fff4e3}.notification-trigger svg{width:24px;height:24px}.notification-trigger>b,.notification-trigger>i,.review-inbox header b{display:grid;place-items:center;min-width:18px;height:18px;padding:0 3px;border-radius:12px;background:#ff8b00;color:#fff;font-size:11px;font-style:normal}.notification-trigger>b,.notification-trigger>i{position:absolute;top:0;right:0}.review-inbox{position:absolute;top:52px;right:0;box-sizing:border-box;width:min(420px,calc(100vw - 32px));border:1px solid #e0e6eb;border-top:3px solid #ff9700;border-radius:9px;background:#fff;box-shadow:0 14px 38px #10253626;z-index:45}.review-inbox header{display:flex;align-items:center;gap:12px;padding:16px;border-bottom:1px solid #edf0f3}.review-inbox header strong{font-size:16px}.review-inbox header>span{display:flex;align-items:center;gap:6px;margin-left:auto;font-size:12px;color:#75818a}.review-inbox button{border:0;background:transparent;color:#b96a00;font:inherit;font-size:12px;cursor:pointer}.review-inbox header>button{color:#7d8992;font-size:22px}.inbox-messages{max-height:min(55vh,480px);overflow:auto}.inbox-messages article{display:flex;align-items:flex-start;gap:10px;padding:17px 16px;border-bottom:1px solid #edf0f3}.inbox-messages article>i{width:8px;height:8px;flex-shrink:0;margin-top:6px;border-radius:50%;background:#20a25e}.inbox-messages article>i.rejected{background:#e8564d}.inbox-messages article>i.comment{background:#4087cf}.inbox-messages article>div{display:grid;gap:6px;min-width:0;flex:1}.inbox-messages b{font-size:13px}.inbox-messages span,.inbox-messages small{color:#788591;font-size:11px;overflow-wrap:anywhere}.inbox-messages p{margin:0;color:#52616d;font-size:12px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.inbox-messages article>button{flex-shrink:0;padding:4px}.review-inbox footer{padding:13px;text-align:center;color:#81909a;font-size:11px}.review-inbox>p{padding:12px 16px;font-size:12px}.notification-error{color:#b63830}.inbox-empty{text-align:center;color:#7c8992}.review-inbox nav{display:flex;justify-content:space-between;padding:12px 16px;font-size:12px}.review-inbox button:disabled{opacity:.4;cursor:default}.review-arrival{position:fixed;top:16px;left:50%;transform:translateX(-50%);box-sizing:border-box;width:max-content;max-width:calc(100vw - 32px);display:flex;align-items:center;gap:12px;padding:12px 15px;border:1px solid #efd0a0;border-radius:9px;background:#fffcf5;box-shadow:0 8px 24px #10253620;z-index:45}.review-arrival button{min-width:0;border:0;background:none;color:#a85d00;cursor:pointer;font:inherit;font-size:13px;line-height:1.6;white-space:normal;overflow-wrap:anywhere}.review-arrival button:first-child{text-align:left}.review-arrival button:last-child{flex-shrink:0;width:28px;height:28px;padding:0;font-size:20px}button:focus-visible{outline:2px solid #d87900;outline-offset:3px}@media(max-width:640px){.review-inbox{position:fixed;top:112px;right:16px}}
</style>
