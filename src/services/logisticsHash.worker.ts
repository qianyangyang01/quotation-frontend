self.onmessage = async (event: MessageEvent<Blob>) => {
  try {
    const hash = await crypto.subtle.digest('SHA-256', await event.data.arrayBuffer())
    self.postMessage({ hash: [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, '0')).join('') })
  } catch {
    self.postMessage({ error: '文件校验失败，请重新选择原文件' })
  }
}
