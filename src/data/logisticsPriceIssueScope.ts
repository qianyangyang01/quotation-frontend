import type { Price, SourceIssue } from './logisticsRebuild'

export function priceCountryKey(row: Price) { return row.countryCode?.trim().toUpperCase() || row.areaName.trim() }

function matchedIssueRows(rows: Price[], issues: SourceIssue[]) {
  const affected = new Set<Price>(rows.filter(row => Boolean(row.blockingReason)))
  for (const issue of issues.filter(item => item.level === 'error')) {
    const keyed = rows.filter(row => row.rowKey && (row.rowKey === issue.rowKey || row.rowKey === issue.relatedRowKey))
    const matched = keyed.length ? keyed : rows.filter(row => issue.row > 0 && row.sourceRow === issue.row && (!issue.sourceSheet || row.sourceSheet === issue.sourceSheet))
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

export function priceIssueRowIndexes(rows: Price[], issues: SourceIssue[]) {
  const affected = matchedIssueRows(rows, issues)
  // Keep both sides of an overlapping interval together for review.
  for (const issue of issues.filter(item => item.level === 'error' && /重叠/.test(item.message))) {
    const anchors = matchedIssueRows(rows, [issue])
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
