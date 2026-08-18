export const QUOTATION_TEMPLATES_STORAGE_KEY = 'milano.quotation.personal-templates.v1'
export const QUOTATION_TEMPLATES_UPDATED_EVENT = 'milano:quotation-personal-templates-updated'

export interface QuotationTemplateOwner {
  account?: string
  name?: string
}

export interface QuotationTemplateSelectionItem {
  country: string
  countryCode: string
  channelKey: string
  ruleId: number
  rule: string
  carrier: string
  transport: string
  channelCode: string
}

export interface QuotationPersonalTemplate {
  id: string
  name: string
  description?: string
  ownerKey: string
  ownerAccount: string
  ownerName: string
  items: QuotationTemplateSelectionItem[]
  createdAt: string
  updatedAt: string
}

export interface QuotationTemplateCreateInput {
  name: string
  description?: string
  items: QuotationTemplateSelectionItem[]
}

export interface QuotationTemplateUpdateInput {
  name?: string
  description?: string
  items?: QuotationTemplateSelectionItem[]
}

type UnknownRecord = Record<string, unknown>

interface NormalizedOwner {
  ownerKey: string
  ownerAccount: string
  ownerName: string
}

function asRecord(value: unknown): UnknownRecord | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as UnknownRecord
    : null
}

function cleanText(value: unknown) {
  return typeof value === 'string' ? value.trim() : value == null ? '' : String(value).trim()
}

function normalizedAccount(value: unknown) {
  return cleanText(value).toUpperCase()
}

function ownerKeyFrom(account: string, name: string) {
  if (account) return `ACCOUNT:${account}`
  const normalizedName = name.toLocaleUpperCase('zh-CN') || 'UNKNOWN'
  return `NAME:${normalizedName}`
}

function normalizeStoredOwnerKey(value: unknown) {
  const key = cleanText(value)
  if (!key) return ''
  const separator = key.indexOf(':')
  if (separator < 0) return `ACCOUNT:${key.toUpperCase()}`
  const prefix = key.slice(0, separator).toUpperCase()
  const identity = key.slice(separator + 1).trim()
  if (prefix === 'ACCOUNT') return `ACCOUNT:${identity.toUpperCase()}`
  if (prefix === 'NAME') return `NAME:${identity.toLocaleUpperCase('zh-CN')}`
  return key
}

export function quotationTemplateOwnerKey(owner: QuotationTemplateOwner) {
  const account = normalizedAccount(owner.account)
  const name = cleanText(owner.name)
  return ownerKeyFrom(account, name)
}

function normalizeOwner(owner: QuotationTemplateOwner): NormalizedOwner {
  const ownerAccount = normalizedAccount(owner.account)
  const ownerName = cleanText(owner.name)
  return {
    ownerKey: ownerKeyFrom(ownerAccount, ownerName),
    ownerAccount,
    ownerName,
  }
}

function normalizeOwnerFromRecord(raw: UnknownRecord): NormalizedOwner {
  const ownerAccount = normalizedAccount(raw.ownerAccount ?? raw.account ?? raw.salespersonAccount)
  const ownerName = cleanText(raw.ownerName ?? raw.salespersonName)
  const storedKey = normalizeStoredOwnerKey(raw.ownerKey)
  return {
    ownerKey: storedKey || ownerKeyFrom(ownerAccount, ownerName),
    ownerAccount,
    ownerName,
  }
}

function finiteRuleId(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function normalizeSelectionItem(value: unknown): QuotationTemplateSelectionItem | null {
  const raw = asRecord(value)
  if (!raw) return null
  const country = cleanText(raw.country ?? raw.countryName)
  const countryCode = cleanText(raw.countryCode ?? raw.code).toUpperCase()
  const ruleId = finiteRuleId(raw.ruleId)
  const rule = cleanText(raw.rule ?? raw.ruleName)
  const carrier = cleanText(raw.carrier)
  const transport = cleanText(raw.transport ?? raw.channel)
  const channelCode = cleanText(raw.channelCode).toUpperCase()
  const storedChannelKey = cleanText(raw.channelKey ?? raw.key)
  if (!country || (!storedChannelKey && !ruleId && !carrier && !transport && !channelCode)) return null
  const channelKey = storedChannelKey
    || `${ruleId}::${carrier}::${channelCode || transport}`
  return { country, countryCode, channelKey, ruleId, rule, carrier, transport, channelCode }
}

function normalizeSelectionItems(value: unknown) {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  return value.reduce<QuotationTemplateSelectionItem[]>((items, candidate) => {
    const item = normalizeSelectionItem(candidate)
    if (!item) return items
    const identity = `${item.country}\u0000${item.channelKey}`
    if (!seen.has(identity)) {
      seen.add(identity)
      items.push(item)
    }
    return items
  }, [])
}

function normalizedTimestamp(value: unknown, fallback: string) {
  const text = cleanText(value)
  if (!text) return fallback
  const time = Date.parse(text)
  return Number.isFinite(time) ? new Date(time).toISOString() : fallback
}

function templateId(value: unknown) {
  const existing = cleanText(value)
  if (existing) return existing
  return createId()
}

function normalizeTemplate(value: unknown): QuotationPersonalTemplate | null {
  const raw = asRecord(value)
  if (!raw) return null
  const name = cleanText(raw.name ?? raw.title ?? raw.templateName)
  if (!name) return null
  const now = new Date().toISOString()
  const createdAt = normalizedTimestamp(raw.createdAt, now)
  const updatedAt = normalizedTimestamp(raw.updatedAt, createdAt)
  const owner = normalizeOwnerFromRecord(raw)
  const description = cleanText(raw.description)
  return {
    id: templateId(raw.id),
    name,
    ...(description ? { description } : {}),
    ...owner,
    items: normalizeSelectionItems(raw.items ?? raw.selections ?? raw.channels),
    createdAt,
    updatedAt,
  }
}

function parseStoredTemplates(value: unknown) {
  if (Array.isArray(value)) return value
  const record = asRecord(value)
  if (!record) return []
  if (Array.isArray(record.templates)) return record.templates
  if (Array.isArray(record.rows)) return record.rows
  return []
}

function readAllTemplates(): QuotationPersonalTemplate[] {
  if (typeof window === 'undefined') return []
  try {
    const stored = window.localStorage.getItem(QUOTATION_TEMPLATES_STORAGE_KEY)
    if (!stored) return []
    return parseStoredTemplates(JSON.parse(stored))
      .map(normalizeTemplate)
      .filter((template): template is QuotationPersonalTemplate => template !== null)
  } catch {
    return []
  }
}

function createId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function notifyTemplatesUpdated(ownerKey: string) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(QUOTATION_TEMPLATES_UPDATED_EVENT, { detail: { ownerKey } }))
}

function writeAllTemplates(templates: QuotationPersonalTemplate[], ownerKey: string) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(QUOTATION_TEMPLATES_STORAGE_KEY, JSON.stringify(templates))
    notifyTemplatesUpdated(ownerKey)
  }
}

function ownerTemplates(owner: QuotationTemplateOwner) {
  const ownerKey = quotationTemplateOwnerKey(owner)
  return readAllTemplates()
    .filter(template => template.ownerKey === ownerKey)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.name.localeCompare(b.name, 'zh-CN'))
}

export function loadQuotationTemplates(owner: QuotationTemplateOwner) {
  return ownerTemplates(owner)
}

export function createQuotationTemplate(owner: QuotationTemplateOwner, input: QuotationTemplateCreateInput) {
  const normalizedOwner = normalizeOwner(owner)
  const now = new Date().toISOString()
  const name = cleanText(input.name) || '未命名报价模板'
  const description = cleanText(input.description)
  const template: QuotationPersonalTemplate = {
    id: createId(),
    name,
    ...(description ? { description } : {}),
    ...normalizedOwner,
    items: normalizeSelectionItems(input.items),
    createdAt: now,
    updatedAt: now,
  }
  const all = readAllTemplates()
  all.push(template)
  writeAllTemplates(all, normalizedOwner.ownerKey)
  return template
}

export function updateQuotationTemplate(
  owner: QuotationTemplateOwner,
  id: string,
  patch: QuotationTemplateUpdateInput,
) {
  const ownerKey = quotationTemplateOwnerKey(owner)
  const all = readAllTemplates()
  const index = all.findIndex(template => template.id === id && template.ownerKey === ownerKey)
  if (index < 0) return null
  const current = all[index]
  const name = patch.name === undefined ? current.name : cleanText(patch.name) || current.name
  const description = patch.description === undefined ? current.description : cleanText(patch.description)
  const items = patch.items === undefined ? current.items : normalizeSelectionItems(patch.items)
  const updated: QuotationPersonalTemplate = {
    ...current,
    name,
    ...(description ? { description } : {}),
    items,
    updatedAt: new Date().toISOString(),
  }
  if (!description) delete updated.description
  all[index] = updated
  writeAllTemplates(all, ownerKey)
  return updated
}

export function deleteQuotationTemplate(owner: QuotationTemplateOwner, id: string) {
  const ownerKey = quotationTemplateOwnerKey(owner)
  const all = readAllTemplates()
  const remaining = all.filter(template => !(template.id === id && template.ownerKey === ownerKey))
  if (remaining.length === all.length) return false
  writeAllTemplates(remaining, ownerKey)
  return true
}

export function copyQuotationTemplate(owner: QuotationTemplateOwner, id: string, name?: string) {
  const source = ownerTemplates(owner).find(template => template.id === id)
  if (!source) return null
  return createQuotationTemplate(owner, {
    name: cleanText(name) || `${source.name}（副本）`,
    description: source.description,
    items: source.items,
  })
}
