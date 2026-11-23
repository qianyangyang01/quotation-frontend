import current from '@/data/applicationRelease.json'

export interface ApplicationRelease {
  version: string
  publishedOn: string
  policy: 'optional' | 'required'
  requiredEpoch: string
  title: string
  features: Array<{ title: string; description: string; location: string }>
}
const short = (value: unknown, limit: number): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= limit
export function parseApplicationRelease(value: unknown): ApplicationRelease | undefined {
  if (!value || typeof value !== 'object') return
  const r = value as ApplicationRelease
  if (!short(r.version, 100) || !/^[a-zA-Z0-9.-]+$/.test(r.version) || !short(r.publishedOn, 10) || !/^\d{4}-\d{2}-\d{2}$/.test(r.publishedOn)
    || !['optional', 'required'].includes(r.policy) || !short(r.requiredEpoch, 100) || !/^[a-zA-Z0-9-]+$/.test(r.requiredEpoch)
    || !short(r.title, 100) || !Array.isArray(r.features) || r.features.length < 1 || r.features.length > 20
    || !r.features.every(f => f && short(f.title, 100) && short(f.description, 600) && short(f.location, 180))) return
  return r
}
export const currentApplicationRelease = parseApplicationRelease(current)!
