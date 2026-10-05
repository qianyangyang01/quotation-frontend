import { api } from './http'
import { loadQuotePhotos, releaseQuotePhotos, type QuoteLocalPhoto } from './quoteLocalPhotos'
import { validQuoteSheetPhotos, type QuoteSheetPhoto } from '@/data/quoteSheetPhotos'

export async function saveQuoteSheetPhotos(photos: readonly QuoteLocalPhoto[]): Promise<QuoteSheetPhoto[]> {
  const saved: QuoteSheetPhoto[] = []
  for (const photo of photos) {
    if (!photo.assetId) {
      if (!photo.file) throw new Error('报价单图片已失效，请重新添加后保存')
      const form = new FormData()
      form.append('file', photo.file, photo.name)
      const result = await api.post<QuoteSheetPhoto>('/quotation-sheet-photos', form)
      if (!validQuoteSheetPhotos([result])) throw new Error('图片保存结果无效，请重试')
      photo.assetId = result.assetId
    }
    saved.push({ assetId: photo.assetId, name: photo.name.slice(0, 255) })
  }
  return saved
}

export async function loadSavedQuoteSheetPhotos(saved: readonly QuoteSheetPhoto[], signal: AbortSignal): Promise<QuoteLocalPhoto[]> {
  if (!validQuoteSheetPhotos(saved)) throw new Error('已保存的报价单图片格式错误')
  const loaded: QuoteLocalPhoto[] = []
  try {
    for (const photo of saved) {
      const response = await fetch(`/api/v1/assets/${photo.assetId}`, { credentials: 'same-origin', signal })
      if (!response.ok) throw new Error('报价单图片读取失败，请重试')
      const blob = await response.blob()
      const [image] = await loadQuotePhotos([new File([blob], photo.name, { type: blob.type })], true)
      image!.assetId = photo.assetId
      loaded.push(image!)
      if (signal.aborted) throw new Error('图片读取已取消')
    }
    return loaded
  } catch (error) { releaseQuotePhotos(loaded); throw error }
}
