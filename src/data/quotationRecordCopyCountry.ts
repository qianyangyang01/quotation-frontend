/** Display the saved region without repeating its country prefix. */
export function quotationRecordCopyCountry(country: string, region?: string) {
  const name = country.trim(), area = region?.trim()
  if (!area || ['全国统一', '未保存', name].includes(area)) return name
  return area.startsWith(name) ? area : `${name} · ${area}`
}
