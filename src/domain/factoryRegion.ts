import divisions from '@/data/geography/pca-code.json'

export interface FactoryRegion {
  province?: string
  city?: string
  regionLabel: string
  continuationRateCny?: 1.5 | 3
}

type Place = readonly [province: string, city: string, district: string]
type Entry = { place: Place; level: number }
type Hit = { start: number; end: number; places: Place[] }
type Trie = { next: Map<string, Trie>; token?: string }
type Division = { code: string; name: string; children?: Division[] }
const inside = new Set(['江苏省', '浙江省', '上海市', '安徽省'])
const municipalities = new Set(['北京市', '天津市', '上海市', '重庆市'])
const placeholders = new Set(['市辖区', '县', '省直辖县级行政区划', '自治区直辖县级行政区划'])
// These district abbreviations commonly occur in brands. Require their parent.
const commonWords = new Set(('和平 友好 友谊 平安 前进 新兴 光明 南山 龙华 新民 乐业 莲花 华容 博爱 大同 永安 万年 吉利 同心 红旗 红星 长寿 安宁 上街 新会 惠民 海丰 中阳 中江 中宁 中方 兴业 金平 金秀 北林 东安 东宁 汉阴 寿阳 平顺 清新 瑞安 南丰 长乐 宝丰 庆元 富民 万荣 富源 永新 和顺 德保 广安 美姑').split(' '))
const fullNames = new Map<string, Place[]>()
const shortNames = new Map<string, Entry[]>()
const shortByFirst = new Map<string, string[]>()
const trie: Trie = { next: new Map() }

function abbreviation(name: string, level: number) {
  if (level === 0) {
    const autonomous: Record<string, string> = { 内蒙古自治区: '内蒙古', 广西壮族自治区: '广西', 西藏自治区: '西藏', 宁夏回族自治区: '宁夏', 新疆维吾尔自治区: '新疆' }
    if (autonomous[name]) return autonomous[name]
  }
  if (/[省市区县]$/.test(name)) {
    const short = name.slice(0, -1)
    if (short.length >= 2 && !short.endsWith('自治')) return short
  }
}

function add(name: string, place: Place, level: number) {
  if (placeholders.has(name)) return
  fullNames.set(name, [...(fullNames.get(name) ?? []), place])
  const short = abbreviation(name, level)
  if (short) shortNames.set(short, [...(shortNames.get(short) ?? []), { place, level }])
}

for (const province of divisions as Division[]) {
  const p = province.name
  const municipal = municipalities.has(p) ? p : ''
  add(p, [p, municipal, ''], 0)
  for (const city of province.children ?? []) {
    const c = municipal || (placeholders.has(city.name) ? '' : city.name)
    if (!municipal) add(city.name, [p, c, ''], 1)
    for (const district of city.children ?? []) {
      // A few cities contain township rows; this is not a nationwide town index.
      if (district.code.length === 6) add(district.name, [p, c, district.name], 2)
    }
  }
}
for (const [name, short] of [['香港特别行政区', '香港'], ['澳门特别行政区', '澳门'], ['台湾省', '台湾']] as const) {
  fullNames.set(name, [[name, '', '']])
  shortNames.set(short, [{ place: [name, '', ''], level: 0 }])
}
for (const token of fullNames.keys()) {
  let node = trie
  for (const char of token) {
    if (!node.next.has(char)) node.next.set(char, { next: new Map() })
    node = node.next.get(char)!
  }
  node.token = token
}
for (const token of shortNames.keys()) shortByFirst.set(token[0]!, [...(shortByFirst.get(token[0]!) ?? []), token])
for (const tokens of shortByFirst.values()) tokens.sort((a, b) => b.length - a.length)

function merge(a: Place, b: Place): Place | null {
  if (a.some((value, index) => value && b[index] && value !== b[index])) return null
  return [a[0] || b[0], a[1] || b[1], a[2] || b[2]]
}

function unique(places: Place[]) {
  return [...new Map(places.map(place => [place.join('|'), place])).values()]
}

function findHits(text: string): Hit[] {
  const matches: Hit[] = []
  for (let start = 0; start < text.length; start++) {
    let node: Trie | undefined = trie
    for (let end = start; end < text.length; end++) {
      node = node.next.get(text[end]!)
      if (!node) break
      if (node.token) matches.push({ start, end: end + 1, places: fullNames.get(node.token)! })
    }
  }
  const hits = matches.filter(hit => !matches.some(other => other.start <= hit.start && other.end >= hit.end && other.end - other.start > hit.end - hit.start))
  const boundaries = new Set([0])
  for (const pattern of [/[([【]/g, /\|+|(?:有限公司|商行|厂|店)[-—;；]+/g, /(?:产地|地址|工厂地址|厂址|所在地|工厂)[:：]/g]) {
    for (const match of text.matchAll(pattern)) boundaries.add(match.index + match[0].length)
  }
  if (text.startsWith('中国')) boundaries.add(/[·—-]/.test(text[2] ?? '') ? 3 : 2)
  const parentEnds = new Set(hits.filter(hit => hit.places.some(place => !place[2])).map(hit => hit.end))
  for (const end of parentEnds) boundaries.add(end)
  const examined = new Set<number>()
  while ([...boundaries].some(start => !examined.has(start))) {
    const start = Math.min(...[...boundaries].filter(value => !examined.has(value)))
    examined.add(start)
    if (start >= text.length || hits.some(hit => hit.start === start)) continue
    for (const token of shortByFirst.get(text[start]!) ?? []) {
      if (!text.startsWith(token, start)) continue
      let choices = shortNames.get(token)!
      const parentContext = parentEnds.has(start)
      if (parentContext) {
        const parents = hits.filter(hit => hit.end === start && hit.places.some(place => !place[2]))
        if (parents.length) {
          choices = choices.filter(entry => parents.some(hit => hit.places.some(parent => merge(parent, entry.place))))
          if (!choices.length) continue
        }
      }
      if (choices.some(entry => entry.level === 0)) choices = choices.filter(entry => entry.level === 0)
      if (!parentContext && commonWords.has(token) && choices.every(entry => entry.level === 2)) continue
      const end = start + token.length
      hits.push({ start, end, places: unique(choices.map(entry => entry.place)) })
      if (choices.some(entry => entry.level < 2)) {
        boundaries.add(end)
        parentEnds.add(end)
        if (/[·—-]/.test(text[end] ?? '')) {
          boundaries.add(end + 1)
          parentEnds.add(end + 1)
        }
      }
      break
    }
  }
  return hits.sort((a, b) => a.start - b.start || b.end - a.end)
}

/** Factory text gives a geographic hint, not verified company dispatch details. */
export function resolveFactoryRegion(factoryInfo: string): FactoryRegion {
  const unknown: FactoryRegion = { regionLabel: '地区待核实' }
  if (typeof factoryInfo !== 'string' || factoryInfo.length > 4000) return unknown
  const text = factoryInfo.normalize('NFKC').replace(/[\r\n]+/g, '|').replace(/\s+/g, '').replace(/^\|+|\|+$/g, '')
  if (!text) return unknown
  const hits = findHits(text)
  if (!hits.length) return unknown
  let candidates: Place[] = [['', '', '']]
  for (const hit of hits) {
    candidates = unique(candidates.flatMap(left => hit.places.flatMap(right => {
      const combined = merge(left, right)
      return combined ? [combined] : []
    })))
    if (!candidates.length) return unknown
  }
  if (candidates.length > 1) {
    candidates = candidates.filter(place => !candidates.some(other => {
      const combined = merge(other, place)
      return other !== place && combined?.join('|') === place.join('|') && other.filter(Boolean).length < place.filter(Boolean).length
    }))
  }
  if (candidates.length !== 1) return unknown
  const [province, prefecture, district] = candidates[0]!
  if (!province) return unknown
  const city = prefecture || (district.endsWith('市') ? district : '')
  return {
    province,
    ...(city ? { city } : {}),
    regionLabel: [province, city !== province ? city : ''].filter(Boolean).join(' / '),
    continuationRateCny: inside.has(province) ? 1.5 : 3,
  }
}
