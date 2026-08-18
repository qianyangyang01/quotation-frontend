export const countryStageOptions = [
  { value: 'common', label: '常用国家' },
  { value: 'standard', label: '一般国家' },
  { value: 'rare', label: '冷门国家' },
] as const

export type CountryStage = (typeof countryStageOptions)[number]['value']

export const continentOptions = ['亚洲', '欧洲', '北美洲', '南美洲', '非洲', '大洋洲'] as const
export type CountryContinent = (typeof continentOptions)[number]

const europe = new Set('AD AL AT AX BA BE BG BY CH CY CZ DE DK EE ES FI FO FR GB GG GI GR HR HU IE IM IS IT JE LI LT LU LV MC MD ME MK MT NL NO PL PT RO RS RU SE SI SK SM UA VA XK'.split(' '))
const africa = new Set('AC AO BF BI BJ BW CD CF CG CI CM CV DJ DZ EG EH ER ET GA GH GM GN GQ GW KE KM LR LS LY MA MG ML MR MU MW MZ NA NE NG RE RW SC SD SH SL SN SO SS ST SZ TA TD TG TN TZ UG YT ZA ZM ZW'.split(' '))
const oceania = new Set('AS AU CK FJ FM GU KI MH MP NC NF NR NU NZ PF PG PN PW SB TK TO TV VU WF WS'.split(' '))
const northAmerica = new Set('AG AI AW BB BM BQ BS BZ CA CR CU CW DM DO GD GL GP GT HN HT JM KN KY LC MQ MS MX NI PA PM PR SV SX TC TT US VC VG VI'.split(' '))
const southAmerica = new Set('AR BO BR CL CO EC FK GF GY PE PY SR UY VE'.split(' '))

const commonCountries = ['美国', '英国', '法国', '澳大利亚']
const standardCountries = new Set([
  '德国', '巴西', '加拿大', '意大利', '西班牙', '日本', '韩国', '新西兰', '墨西哥', '荷兰',
  '波兰', '比利时', '瑞士', '瑞典', '挪威', '丹麦', '奥地利', '爱尔兰', '葡萄牙',
])

export function inferCountryContinent(code = ''): CountryContinent {
  const normalized = code.trim().toUpperCase()
  if (europe.has(normalized)) return '欧洲'
  if (africa.has(normalized)) return '非洲'
  if (oceania.has(normalized)) return '大洋洲'
  if (northAmerica.has(normalized)) return '北美洲'
  if (southAmerica.has(normalized)) return '南美洲'
  return '亚洲'
}

export function defaultCountryStage(country: string): CountryStage {
  if (commonCountries.includes(country)) return 'common'
  if (standardCountries.has(country)) return 'standard'
  return 'rare'
}

export function defaultCountrySortOrder(country: string, stage = defaultCountryStage(country)) {
  if (stage === 'common') return commonCountries.indexOf(country) + 1 || 99
  return stage === 'standard' ? 100 : 1000
}

export function countryStageLabel(stage: CountryStage) {
  return countryStageOptions.find(option => option.value === stage)?.label || '一般国家'
}
