<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { loadQuotePhotos, MAX_QUOTE_PHOTOS, releaseQuotePhotos, type QuoteLocalPhoto } from '@/services/quoteLocalPhotos'
import { capturePhotoPaste, PHOTO_PASTE_HELP, readPhotoClipboard, resolvePhotoPaste } from '@/services/quotePhotoClipboard'

const props = defineProps<{ photos: readonly QuoteLocalPhoto[] }>()
const emit = defineEmits<{ cancel: []; confirm: [photos: QuoteLocalPhoto[]] }>()
const selected = shallowRef([...props.photos])
// Existing photos remain owned by the quotation until confirmation. Only new images belong to this dialog.
const owned = new Set<QuoteLocalPhoto>()
const dialog = ref<HTMLElement>()
const anchor = ref<HTMLElement>()
const teleportTarget = shallowRef<HTMLElement | string>('body')
const fileInput = ref<HTMLInputElement>()
const busy = ref(false)
const error = ref('')
const status = ref('')
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
let disposed = false
const downloads = new AbortController()

async function addPhotos(readFiles: () => Promise<File[]>) {
  if (disposed || busy.value) return
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    const files = await readFiles()
    if (disposed) return
    if (selected.value.length + files.length > MAX_QUOTE_PHOTOS) throw new Error(`最多添加 ${MAX_QUOTE_PHOTOS} 张图片，请先移除不需要的图片。`)
    const loaded = await loadQuotePhotos(files)
    if (disposed) { releaseQuotePhotos(loaded); return }
    loaded.forEach(photo => owned.add(photo))
    selected.value = [...selected.value, ...loaded]
    status.value = selected.value.length === MAX_QUOTE_PHOTOS
      ? `已收集 ${MAX_QUOTE_PHOTOS} 张图片，点击确定统一放入报价单。`
      : `已添加 ${loaded.length} 张，共 ${selected.value.length} 张；可继续粘贴，最后点击确定。`
  } catch (reason) {
    if (!disposed) error.value = reason instanceof Error ? reason.message : '图片读取失败，请重新复制后粘贴'
  } finally { if (!disposed) busy.value = false }
}
function choosePhotos(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length) void addPhotos(async () => files)
}
function pastePhotos() {
  dialog.value?.focus()
  void addPhotos(async () => resolvePhotoPaste(await readPhotoClipboard(), downloads.signal))
}
function paste(event: ClipboardEvent) {
  event.preventDefault()
  event.stopPropagation()
  if (busy.value || disposed) return
  const data = capturePhotoPaste(event.clipboardData)
  if (!data.files.length && !/<img\b/i.test(data.html)) { error.value = PHOTO_PASTE_HELP; return }
  void addPhotos(() => resolvePhotoPaste(data, downloads.signal))
}
function removePhoto(index: number) {
  if (busy.value) return
  const removed = selected.value[index]
  if (!removed) return
  if (owned.delete(removed)) releaseQuotePhotos([removed])
  selected.value = selected.value.filter((_, i) => i !== index)
  error.value = ''
  status.value = `已移除图片，还可添加 ${MAX_QUOTE_PHOTOS - selected.value.length} 张。`
  nextTick(() => dialog.value?.focus())
}
function cancel() { disposed = true; downloads.abort(); emit('cancel') }
function confirm() {
  if (busy.value || disposed) return
  disposed = true
  owned.clear() // Transfer ownership to the quotation only after explicit confirmation.
  emit('confirm', [...selected.value])
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); cancel(); return }
  if (event.key !== 'Tab') return
  const buttons = Array.from(dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
  const first = buttons[0], last = buttons.at(-1)
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.value)) { event.preventDefault(); first?.focus() }
}
onMounted(async () => {
  // A native modal makes body-level overlays inert; keep this picker inside its owning modal.
  teleportTarget.value = anchor.value?.closest<HTMLDialogElement>('dialog[open]') ?? 'body'
  await nextTick()
  if (!disposed) dialog.value?.focus()
})
onBeforeUnmount(() => {
  disposed = true
  downloads.abort()
  releaseQuotePhotos([...owned])
  owned.clear()
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<template>
  <span ref="anchor" hidden aria-hidden="true" />
  <Teleport :to="teleportTarget">
    <div class="photo-picker-overlay">
      <section ref="dialog" class="photo-picker" role="dialog" aria-modal="true" aria-labelledby="photo-picker-title" aria-describedby="photo-picker-help" tabindex="-1" @paste="paste" @keydown="keydown">
        <header><div><h3 id="photo-picker-title">添加报价单图片</h3><p>收集好图片后，一次确认放入报价单</p></div><button type="button" aria-label="关闭图片弹窗" @click="cancel">×</button></header>
        <div class="photo-picker-content">
          <div class="photo-picker-tools">
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden aria-label="选择临时商品图片" @change="choosePhotos">
            <button type="button" :disabled="busy || selected.length >= MAX_QUOTE_PHOTOS" @click="fileInput?.click()">选择图片</button>
            <button type="button" :disabled="busy || selected.length >= MAX_QUOTE_PHOTOS" @click="pastePhotos">粘贴图片</button>
            <strong>已选 {{ selected.length }} / {{ MAX_QUOTE_PHOTOS }} 张</strong>
          </div>
          <p id="photo-picker-help">从石墨逐张复制图片，回到此弹窗按 Ctrl+V（Mac：⌘V）即可继续添加，不必关闭弹窗。也可以一次选择多张文件。</p>
          <p class="photo-picker-note">支持 JPG / PNG / WebP，每张不超过 10MB；保存报价时会一同保存图片，可在报价记录中预览和复制。</p>
          <p v-if="error" class="photo-picker-error" role="alert">{{ error }}</p>
          <p v-if="busy || status" class="photo-picker-status" role="status">{{ busy ? '正在读取图片…' : status }}</p>
          <div v-if="selected.length" class="photo-picker-grid">
            <figure v-for="(photo, index) in selected" :key="photo.url"><img :src="photo.url" :alt="`待确认图片 ${index + 1}`"><figcaption><span>图片 {{ index + 1 }}</span><button type="button" :aria-label="`移除第 ${index + 1} 张图片`" :disabled="busy" @click="removePhoto(index)">移除</button></figcaption></figure>
          </div>
          <div v-else class="photo-picker-empty" @click="dialog?.focus()">在这里连续粘贴图片<br><small>先复制一张图片，再按 Ctrl+V</small></div>
        </div>
        <footer><span>点击确定后才会更新报价单，取消保留原图。</span><button type="button" @click="cancel">取消</button><button type="button" class="photo-picker-confirm" :disabled="busy" @click="confirm">确定（{{ selected.length }} 张）</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.photo-picker-overlay{position:fixed;inset:0;z-index:3000;display:flex;align-items:center;justify-content:center;padding:20px;background:#18253580}.photo-picker{width:760px;max-width:100%;max-height:calc(100dvh - 40px);display:flex;flex-direction:column;border-radius:12px;background:#fff;color:#243440;box-shadow:0 20px 70px #0004;outline:none;font-size:13px}.photo-picker header,.photo-picker footer{display:flex;align-items:center;gap:12px;padding:18px 22px}.photo-picker header{justify-content:space-between;border-bottom:1px solid #e5e9ed}.photo-picker h3{margin:0;font-size:18px}.photo-picker header p{margin:6px 0 0;color:#71808b}.photo-picker button{padding:9px 14px;border:1px solid #d7dce1;border-radius:6px;background:#fff;color:#243440;font:inherit;cursor:pointer}.photo-picker button:disabled{opacity:.5;cursor:not-allowed}.photo-picker button:focus-visible{outline:2px solid #f58220;outline-offset:2px}.photo-picker-content{overflow:auto;padding:20px 22px}.photo-picker-tools{display:flex;align-items:center;gap:10px}.photo-picker-tools strong{margin-left:auto;color:#ad5811}.photo-picker-content p{line-height:1.7}.photo-picker-note{color:#788591;font-size:12px}.photo-picker-error{padding:10px;background:#fff0e6;color:#a44818;border-radius:6px}.photo-picker-status{color:#287a4d}.photo-picker-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:16px}.photo-picker-grid figure{margin:0;padding:9px;border:1px solid #dfe5eb;border-radius:8px}.photo-picker-grid img{display:block;width:100%;height:112px;object-fit:contain;background:#f6f8fa}.photo-picker-grid figcaption{display:flex;align-items:center;justify-content:space-between;margin-top:8px}.photo-picker-grid button{padding:4px 8px;color:#af4c22}.photo-picker-empty{padding:50px 12px;text-align:center;line-height:2;border:2px dashed #d6dee6;border-radius:8px;background:#fafbfc;color:#657584}.photo-picker-empty small{font-size:12px}.photo-picker footer{border-top:1px solid #e5e9ed;flex-wrap:wrap}.photo-picker footer span{flex:1;min-width:180px;color:#788591;font-size:12px}.photo-picker .photo-picker-confirm{background:#f58220;border-color:#f58220;color:#fff}@media(max-width:600px){.photo-picker-overlay{padding:10px}.photo-picker{max-height:calc(100dvh - 20px)}.photo-picker-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.photo-picker-content{padding:14px}.photo-picker header,.photo-picker footer{padding:14px}}
</style>
