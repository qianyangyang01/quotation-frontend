import type { Price, SourceIssue } from './logisticsRebuild'

export function priceCountryKey(row: Price) { return row.countryCode?.trim().toUpperCase() || row.areaName.trim() }

export function issueLocations(rows: Price[], issue: SourceIssue) {
  const legacy = issue.message.match(/原始行号[：:]\s*\[([0-9,，\s]+)\]/)
  const numbers = [...new Set((issue.sourceRows?.length ? issue.sourceRows : issue.row > 0 ? [issue.row] : legacy ? legacy[1]!.split(/[,，\s]+/).map(Number) : []).filter(n => Number.isInteger(n) && n > 0))]
  const keyed = rows.filter(row => row.rowKey && (row.rowKey === issue.rowKey || row.rowKey === issue.relatedRowKey))
  if (keyed.length) {
    const locations = keyed.map(row => ({ sheet: row.sourceSheet || '', row: row.sourceRow || 0 }))
    if (issue.relatedSourceRow && !locations.some(location => location.row === issue.relatedSourceRow && location.sheet === issue.relatedSourceSheet)) locations.push({ sheet: issue.relatedSourceSheet || '', row: issue.relatedSourceRow })
    return locations
  }
  const sheets = [...new Set(rows.map(row => row.sourceSheet).filter(Boolean))]
  const sheet = issue.sourceSheet || (sheets.length === 1 ? sheets[0]! : '')
  const locations = numbers.map(row => ({ sheet, row }))
  if (issue.relatedSourceRow) locations.push({ sheet: issue.relatedSourceSheet || '', row: issue.relatedSourceRow })
  return locations
}

export function rowsForPriceIssue(rows: Price[], issue: SourceIssue) {
  const locations = issueLocations(rows, issue)
  return rows.filter(row => locations.some(location => {
    if (row.sourceRow !== location.row || location.row <= 0) return false
    if (location.sheet) return row.sourceSheet === location.sheet
    // A row number repeated across sheets is a candidate, not an exact location.
    return rows.filter(candidate => candidate.sourceRow === location.row).length === 1
  }))
}

export function collectPriceReviewIssues(rows: Price[], sourceIssues: SourceIssue[], blockingReasons: string[]) {
  const issues = [...sourceIssues]
  for (const row of rows) {
    for (const reason of (row.blockingReason || '').split('；').map(value => value.trim()).filter(Boolean)) {
      if (!issues.some(issue => issue.level === 'error' && rowsForPriceIssue(rows, issue).includes(row) && issue.message.includes(reason))) {
        issues.push({ row: row.sourceRow || 0, sourceSheet: row.sourceSheet, rowKey: row.rowKey, field: '计费规则', message: reason, level: 'error' })
      }
    }
  }
  for (const reason of blockingReasons) if (reason && !issues.some(issue => issue.level === 'error' && issue.message.includes(reason))) {
    issues.push({ row: 0, field: '渠道规则', message: reason, level: 'error' })
  }
  return issues
}

export function priceIssueLocationLabel(rows: Price[], issue: SourceIssue) {
  const locations = issueLocations(rows, issue)
  if (!locations.length) return issue.sourceSheet ? `Sheet「${issue.sourceSheet}」· 工作表级问题，未提供具体行号` : '渠道级或来源未定位问题：未提供 Sheet / 行号'
  return locations.map(location => `Sheet「${location.sheet || '待确认（同一行号可能属于多个工作表）'}」· 第 ${location.row} 行`).join('；')
}

function matchedIssueRows(rows: Price[], issues: SourceIssue[], includeBlocking = true) {
  const affected = new Set<Price>(includeBlocking ? rows.filter(row => Boolean(row.blockingReason)) : [])
  for (const issue of issues.filter(item => item.level === 'error')) {
    const matched = rowsForPriceIssue(rows, issue)
    matched.forEach(row => affected.add(row))
  }
  return affected
}

export function priceIssueCountries(rows: Price[], issues: SourceIssue[]) {
  const affected = matchedIssueRows(rows, issues)
  const countries = new Map<string, { key: string; label: string }>()
  for (const row of affected) {
    const key = priceCountryKey(row)
    if (key) countries.set(key, { key, label: row.areaName || key })
  }
  return [...countries.values()]
}

export function priceIssueRowIndexes(rows: Price[], issues: SourceIssue[], includeBlocking = true) {
  const affected = matchedIssueRows(rows, issues, includeBlocking)
  // Keep both sides of an overlapping interval together for review.
  for (const issue of issues.filter(item => item.level === 'error' && /重叠/.test(item.message))) {
    const anchors = matchedIssueRows(rows, [issue], false)
    for (const anchor of anchors) for (const row of rows) {
      if (priceCountryKey(row) !== priceCountryKey(anchor) || ['zoneName', 'originRegion', 'sourceOriginRegion', 'sourceProductCode', 'currency'].some(key => (row[key as keyof Price] || '') !== (anchor[key as keyof Price] || ''))) continue
      const start = Math.max(row.weightFromKg, anchor.weightFromKg), end = Math.min(row.weightToKg, anchor.weightToKg)
      const includes = (item: Price, value: number) => (value > item.weightFromKg || item.weightFromInclusive !== false) && (value < item.weightToKg || item.weightToInclusive !== false)
      if (start < end || (start === end && includes(row, start) && includes(anchor, start))) affected.add(row)
    }
  }
  return new Set(rows.flatMap((row, index) => affected.has(row) ? [index] : []))
}

export function prioritizePriceIssueRows(rows: Price[], countries: Array<{ key: string }>, issueIndexes: Set<number>) {
  const keys = new Set(countries.map(country => country.key))
  return rows.map((row, index) => ({ row, index, priority: issueIndexes.has(index) ? 0 : keys.has(priceCountryKey(row)) ? 1 : 2 }))
    .sort((a, b) => a.priority - b.priority || a.index - b.index).map(item => item.row)
}

export function priceRowsForIssueCountries(rows: Price[], countries: Array<{ key: string }>) {
  const keys = new Set(countries.map(country => country.key))
  return keys.size ? rows.filter(row => keys.has(priceCountryKey(row))) : rows
}
