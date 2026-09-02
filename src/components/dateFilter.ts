export function parseIsoDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  if (Number.isNaN(date.getTime()) || date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null
  return date
}

export function isoDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function isDateDisabled(value: string, min?: string, max?: string) {
  return Boolean((min && value < min) || (max && value > max))
}

export function calendarMonthDays(cursor: Date) {
  const year = cursor.getFullYear()
  const month = cursor.getMonth()
  const leading = (new Date(year, month, 1).getDay() + 6) % 7
  const count = new Date(year, month + 1, 0).getDate()
  return [
    ...Array.from({ length: leading }, () => null),
    ...Array.from({ length: count }, (_, index) => {
      const date = new Date(year, month, index + 1)
      return { day: index + 1, value: isoDate(date) }
    }),
  ]
}
