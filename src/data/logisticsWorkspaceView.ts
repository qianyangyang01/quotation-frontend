import type {
  LogisticsChannelRecord,
  LogisticsChannelVersionRecord,
  LogisticsWorkspaceState,
} from './logisticsRepository'

export const logisticsRuleTabs = ['物流商', '物流渠道', '运费规则', '国家区域', '重量限制', '运费试算'] as const
export type LogisticsRuleTab = typeof logisticsRuleTabs[number]

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
