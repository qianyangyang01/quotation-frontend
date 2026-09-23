export type ImportCoverage = {
  existingChannels: number
  coveredChannels: number
  missingCount: number
  partial: boolean
  token: string
  missingChannels: Array<{ channelId: string; providerName: string; channelName: string; versionId: string; versionNumber: number; sourceFile: string }>
}

export function coveragePublishInput(coverage: ImportCoverage | undefined, confirmed: boolean) {
  if (!coverage) throw new Error('尚未完成导入覆盖核对，请重新打开批次后发布')
  if (coverage.partial && !confirmed) throw new Error('请核对未更新渠道，并确认仅发布本批渠道')
  return { coverageToken: coverage.token, partialUpdateConfirmed: coverage.partial && confirmed }
}

export function coveragePublishSummary(coverage: ImportCoverage | undefined) {
  return coverage?.partial ? `；另有 ${coverage.missingCount} 个现行渠道未纳入本批，仍沿用原价格` : ''
}
