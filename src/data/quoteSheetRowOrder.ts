/** Position new batch summaries before their own routes; leave unrelated/manual row order intact. */
export function placeAverageRowsBeforeMembers(order: string[], plans: Array<{ key: string; members: string[] }>): string[] {
  const before = new Map<string, string[]>(), moving = new Set<string>()
  for (const plan of plans) {
    const members = new Set(plan.members)
    const anchor = order.find(key => members.has(key))
    // Summary-only sheets may have no visible source rows. Keep their saved position.
    if (!anchor) continue
    before.set(anchor, [...(before.get(anchor) ?? []), plan.key])
    moving.add(plan.key)
  }
  return order.filter(key => !moving.has(key)).flatMap(key => [...(before.get(key) ?? []), key])
}

/** Stable snapshot identities distinguish saved routes from average plans. */
export function captureQuoteRowOrder(keys: string[] | undefined, optionId: (key: string) => string | undefined): string[] | undefined {
  return keys?.flatMap(key => {
    if (key.startsWith('average:')) return [key]
    const id = optionId(key)
    return id ? [`option:${id}`] : []
  })
}

export function restoreQuoteRowOrder(order: string[] | undefined, rowKey: (id: string) => string | undefined): string[] {
  return (order ?? []).flatMap(id => {
    if (id.startsWith('average:')) return [id]
    const key = id.startsWith('option:') ? rowKey(id.slice(7)) : undefined
    return key ? [key] : []
  })
}

export function orderQuoteRows<T>(rows: T[], order: string[] | undefined, identity: (row: T) => string): T[] {
  const positions = new Map((order ?? []).map((id, index) => [id, index]))
  return [...rows].sort((a, b) => (positions.get(identity(a)) ?? Infinity) - (positions.get(identity(b)) ?? Infinity))
}
