export interface QuotationUser {
  name: string
  account: string
}

const QUOTATION_USER_KEY = 'milano.quotation.user.v1'

export function loadQuotationUser(): QuotationUser {
  try {
    const saved = JSON.parse(localStorage.getItem(QUOTATION_USER_KEY) || 'null') as Partial<QuotationUser> | null
    // 测试环境初始业务员改为钱洋洋；旧版默认占位名自动迁移，真实登录账号仍原样保留。
    if (saved?.name && saved.name !== '报价专员') return { name: saved.name, account: saved.account || '—' }
  } catch {
    // Ignore malformed local state and fall through to the legacy identity.
  }

  return { name: '钱洋洋', account: 'QYY001' }
}
