import type {
  LogisticsChannelRecord,
  LogisticsChannelVersionRecord,
  LogisticsWorkspaceState,
} from './logisticsRepository'

export const logisticsRuleTabs = ['物流商', '物流渠道', '运费规则', '国家区域'] as const
export type LogisticsRuleTab = typeof logisticsRuleTabs[number]
export const logisticsRuleDetailColumns = ['国家区域', '重量范围', '计泡系数', '最长边', '最大周长', '商品限制', '每1000g运费', '挂号费', '预计时效', '状态'] as const

export type LogisticsWorkspaceTab = 'prices' | 'imports' | 'history'

export function logisticsWorkspaceLoadPlan(tab: LogisticsWorkspaceTab) {
  return {
    workspace: true,
    pricePage: tab === 'prices',
    importHistory: false,
  }
}

export function matchesLogisticsProviderScope(scope: 'provider' | 'multi', expectedProvider: string, actualProvider: string) {
  if (scope === 'multi' || !expectedProvider.trim()) return true
  const normalize = (value: string) => value.replace(/\s+/g, '').toLocaleLowerCase()
  return normalize(actualProvider) === normalize(expectedProvider)
}

export function currentPublishedVersion(state: LogisticsWorkspaceState, channel: LogisticsChannelRecord) {
  return state.versions.find(version => version.id === channel.currentVersionId && version.status === 'published')
}

export function latestDraftVersion(state: LogisticsWorkspaceState, channel: LogisticsChannelRecord) {
  return state.versions
    .filter(version => version.channelId === channel.id && version.status === 'draft')
    .sort((left, right) => right.versionNumber - left.versionNumber || right.importedAt.localeCompare(left.importedAt))[0]
}

export function versionBlockingErrors(version?: LogisticsChannelVersionRecord) {
  if (!version) return 0
  if (Number.isFinite(Number(version.errors))) return Math.max(0, Number(version.errors))
  return (version.issues || []).filter(issue => issue.level === 'error').length
}

export function logisticsWorkspaceSummary(state: LogisticsWorkspaceState) {
  const channels = state.channels.filter(channel => !channel.archived)
  return {
    channels: channels.length,
    published: channels.filter(channel => Boolean(currentPublishedVersion(state, channel))).length,
    blockedDrafts: channels.filter(channel => versionBlockingErrors(latestDraftVersion(state, channel)) > 0).length,
  }
}

export function logisticsProviderRows(state: LogisticsWorkspaceState) {
  return state.providers.map(provider => {
    const channels = state.channels.filter(channel => !channel.archived && channel.providerId === provider.id)
    return {
      id: provider.id,
      name: provider.name,
      code: provider.code,
      enabled: provider.enabled,
      channels: channels.length,
      published: channels.filter(channel => Boolean(currentPublishedVersion(state, channel))).length,
      blockedDrafts: channels.filter(channel => versionBlockingErrors(latestDraftVersion(state, channel)) > 0).length,
    }
  }).filter(provider => provider.channels > 0)
}

export function logisticsChannelRows(state: LogisticsWorkspaceState) {
  const providers = new Map(state.providers.map(provider => [provider.id, provider]))
  return state.channels.filter(channel => !channel.archived).map(channel => {
    const provider = providers.get(channel.providerId)
    const published = currentPublishedVersion(state, channel)
    const draft = latestDraftVersion(state, channel)
    return {
      channel,
      providerName: provider?.name || '—',
      providerEnabled: Boolean(provider?.enabled),
      published,
      draft,
      blockedErrors: versionBlockingErrors(draft),
    }
  })
}
