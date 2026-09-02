import { stripPurchaseWorkbookImages, type TextOnlyWorkbookProgress, type TextOnlyWorkbookResult } from './purchaseWorkbookTextOnly'

interface WorkerRequest { file: File }
type WorkerResponse = { type: 'progress'; progress: TextOnlyWorkbookProgress }
  | { type: 'result'; result: TextOnlyWorkbookResult }
  | { type: 'error'; message: string }

addEventListener('message', async (event: MessageEvent<WorkerRequest>) => {
  try {
    const result = await stripPurchaseWorkbookImages(event.data.file, progress => {
      postMessage({ type: 'progress', progress } satisfies WorkerResponse)
    })
    postMessage({ type: 'result', result } satisfies WorkerResponse)
  } catch (error) {
    postMessage({ type: 'error', message: error instanceof Error ? error.message : '无图旧数据文件生成失败' } satisfies WorkerResponse)
  }
})
