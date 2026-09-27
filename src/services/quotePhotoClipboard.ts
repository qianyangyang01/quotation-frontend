import { MAX_QUOTE_PHOTOS, MAX_QUOTE_PHOTO_BYTES } from './quoteLocalPhotos'

export const PHOTO_PASTE_HELP = '未读取到图片，请在石墨中打开图片并选择“复制图片”，再回到此处粘贴；也可以截图后粘贴。'
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']
export type PhotoClipboardData = { files: File[]; html: string }

/** Read the event synchronously: the browser clears its data after dispatch. */
export function capturePhotoPaste(data: DataTransfer | null): PhotoClipboardData {
  if (!data) return { files: [], html: '' }
  const files = Array.from(data.files).filter(file => file.type.startsWith('image/'))
  if (!files.length) {
    for (const item of Array.from(data.items)) {
      if (item.kind !== 'file' || !item.type.startsWith('image/')) continue
      const file = item.getAsFile()
      if (file) files.push(file)
    }
  }
  return { files, html: files.length ? '' : data.getData('text/html') }
}

export async function readPhotoClipboard(): Promise<PhotoClipboardData> {
  if (!navigator.clipboard?.read) throw new Error('浏览器不支持直接读取剪贴板，请点击图片区后按 Ctrl+V（Mac：⌘V）粘贴。')
  let items: ClipboardItem[]
  try { items = await navigator.clipboard.read() }
  catch { throw new Error('未能读取剪贴板，请点击图片区后按 Ctrl+V（Mac：⌘V），或允许浏览器读取剪贴板后重试。') }
  const files: File[] = []
  const html: string[] = []
  for (const item of items) {
    // An item may expose the same image in several formats. Import it only once.
    const type = IMAGE_TYPES.find(type => item.types.includes(type)) ?? item.types.find(type => type.startsWith('image/'))
    if (type) files.push(new File([await item.getType(type)], `粘贴图片-${files.length + 1}`, { type }))
    else if (item.types.includes('text/html')) html.push(await (await item.getType('text/html')).text())
  }
  return { files, html: html.join('') }
}

export async function resolvePhotoPaste(data: PhotoClipboardData, signal?: AbortSignal): Promise<File[]> {
  signal?.throwIfAborted()
  if (data.files.length) return data.files
  // A detached template is inert: pasted HTML is never inserted into the page.
  const template = document.createElement('template')
  template.innerHTML = data.html
  const sources = [...new Set(Array.from(template.content.querySelectorAll('img')).map(img => img.getAttribute('src')?.trim() || '').filter(Boolean))]
  if (!sources.length) throw new Error(PHOTO_PASTE_HELP)
  if (sources.length > MAX_QUOTE_PHOTOS) throw new Error(`最多粘贴 ${MAX_QUOTE_PHOTOS} 张商品图片`)
  const files: File[] = []
  for (const source of sources) {
    signal?.throwIfAborted()
    // No server proxy, source cookies, local files, or executable URL schemes.
    if (!/^https:\/\//i.test(source) && !/^data:image\/(png|jpeg|webp);base64,/i.test(source)) throw new Error(PHOTO_PASTE_HELP)
    if (source.startsWith('data:') && source.length > MAX_QUOTE_PHOTO_BYTES * 1.4) throw new Error('每张图片须大于 0 且不超过 10MB')
    const controller = new AbortController()
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    const timer = setTimeout(() => controller.abort(), 15000)
    try {
      const response = await fetch(source, { signal: controller.signal, credentials: 'omit', referrerPolicy: 'no-referrer', mode: 'cors' })
      if (!response.ok || !response.body) throw new Error('图片读取失败')
      const type = (response.headers.get('content-type') || '').split(';')[0]!.trim().toLowerCase()
      if (!IMAGE_TYPES.includes(type)) throw new Error('请选择 JPG、PNG 或 WebP 图片')
      const reader = response.body.getReader()
      const chunks: Uint8Array<ArrayBuffer>[] = []
      let size = 0
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          size += value.byteLength
          if (size > MAX_QUOTE_PHOTO_BYTES) throw new Error('每张图片须大于 0 且不超过 10MB')
          chunks.push(new Uint8Array(value))
        }
      } finally { await reader.cancel().catch(() => undefined) }
      files.push(new File(chunks, `粘贴图片-${files.length + 1}`, { type }))
    } catch {
      throw new Error('图片链接无法读取或图片不符合要求，请在石墨中打开原图并选择“复制图片”，或截图后粘贴。')
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel) }
  }
  return files
}
