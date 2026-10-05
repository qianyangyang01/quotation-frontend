// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { loadSavedQuoteSheetPhotos, saveQuoteSheetPhotos } from './quoteSheetPhotos'
import { api } from './http'
import { loadQuotePhotos, releaseQuotePhotos, type QuoteLocalPhoto } from './quoteLocalPhotos'
vi.mock('./http',()=>({api:{post:vi.fn()}}))
vi.mock('./quoteLocalPhotos',()=>({loadQuotePhotos:vi.fn(),releaseQuotePhotos:vi.fn()}))
const assetId='11111111-2222-3333-4444-555555555555'
const local=():QuoteLocalPhoto=>({url:'blob:local',name:'sample.png',image:new Image(),file:new File(['image'],'sample.png',{type:'image/png'})})
afterEach(()=>{vi.resetAllMocks();vi.unstubAllGlobals()})
it('uploads new images once and reuses references on a failed quotation save retry',async()=>{
  const image=local()
  vi.mocked(api.post).mockResolvedValue({assetId,name:'sample.png'})
  expect(await saveQuoteSheetPhotos([image])).toEqual([{assetId,name:'sample.png'}])
  expect(await saveQuoteSheetPhotos([image])).toEqual([{assetId,name:'sample.png'}])
  expect(api.post).toHaveBeenCalledTimes(1)
  const form=vi.mocked(api.post).mock.calls[0]![1] as FormData
  expect(form.get('file')).toBeInstanceOf(File)
})
it('does not silently drop an image when upload fails',async()=>{
  vi.mocked(api.post).mockRejectedValue(new Error('storage unavailable'))
  await expect(saveQuoteSheetPhotos([local()])).rejects.toThrow('storage unavailable')
})
it('loads authenticated asset images and releases already decoded images on a later failure',async()=>{
  const image=local()
  vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response(new Blob(['png'],{type:'image/png'}))).mockResolvedValueOnce(new Response('',{status:404})))
  vi.mocked(loadQuotePhotos).mockResolvedValue([image])
  await expect(loadSavedQuoteSheetPhotos([{assetId,name:'one'},{assetId,name:'two'}],new AbortController().signal)).rejects.toThrow('读取失败')
  expect(releaseQuotePhotos).toHaveBeenCalledWith([image])
  expect(fetch).toHaveBeenCalledWith(`/api/v1/assets/${assetId}`,expect.objectContaining({credentials:'same-origin'}))
})
