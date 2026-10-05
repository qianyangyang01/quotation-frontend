export type QuoteSheetPhoto = { assetId: string; name: string }
export const QUOTE_PHOTO_ASSET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function validQuoteSheetPhotos(value: unknown): value is QuoteSheetPhoto[] {
  return Array.isArray(value) && value.length <= 6 && value.every(photo => photo &&
    typeof photo.assetId === 'string' && QUOTE_PHOTO_ASSET_ID.test(photo.assetId) &&
    typeof photo.name === 'string' && photo.name.length <= 255)
}
export function quoteSheetAssetPhoto(url?: string): QuoteSheetPhoto[] {
  const id = url?.match(/^\/api\/v1\/assets\/([0-9a-f-]+)$/i)?.[1]
  return id && QUOTE_PHOTO_ASSET_ID.test(id) ? [{ assetId: id, name: '商品图片' }] : []
}
