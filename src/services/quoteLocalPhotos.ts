/** Component-owned, memory-only images. Never include these in a draft or record payload. */
export type QuoteLocalPhoto = { url: string; image: HTMLImageElement; name: string }
export const MAX_QUOTE_PHOTOS = 4
const MAX_BYTES = 10 * 1024 * 1024
export function releaseQuotePhotos(photos: readonly QuoteLocalPhoto[]) {
  photos.forEach(photo => { photo.image.src = ''; URL.revokeObjectURL(photo.url) })
}
export async function loadQuotePhotos(files: readonly File[]): Promise<QuoteLocalPhoto[]> {
  if (!files.length || files.length > MAX_QUOTE_PHOTOS) throw new Error('请选择 1–4 张商品图片')
  for (const file of files) {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) throw new Error('请选择 JPG、PNG 或 WebP 图片')
    if (!file.size || file.size > MAX_BYTES) throw new Error('每张图片须大于 0 且不超过 10MB')
  }
  const loaded: QuoteLocalPhoto[] = []
  try {
    for (const file of files) {
      const photo = { url: URL.createObjectURL(file), image: new Image(), name: file.name }
      loaded.push(photo)
      await new Promise<void>((resolve, reject) => {
        const done = (error?: Error) => {
          clearTimeout(timer); photo.image.onload = null; photo.image.onerror = null
          if (error) reject(error); else resolve()
        }
        const timer = setTimeout(() => done(new Error('商品图片读取超时，请重新选择')), 15000)
        photo.image.onerror = () => done(new Error('商品图片无法读取，请换一张图片'))
        photo.image.onload = () => {
          const { naturalWidth: w, naturalHeight: h } = photo.image
          done(!w || !h || w * h > 40_000_000 ? new Error('图片尺寸无效或过大，请缩小到 4000 万像素以内') : undefined)
        }
        photo.image.src = photo.url
      })
    }
    return loaded
  } catch (error) { releaseQuotePhotos(loaded); throw error }
}
