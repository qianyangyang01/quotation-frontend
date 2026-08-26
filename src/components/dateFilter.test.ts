import { describe, expect, it } from 'vitest'
import { calendarMonthDays, isDateDisabled, isoDate, parseIsoDate } from './dateFilter'

describe('date filter helpers', () => {
  it('parses only real ISO calendar dates', () => {
    expect(isoDate(parseIsoDate('2026-08-26')!)).toBe('2026-08-26')
    expect(parseIsoDate('2026-02-30')).toBeNull()
    expect(parseIsoDate('2026/08/26')).toBeNull()
  })

  it('enforces inclusive minimum and maximum dates', () => {
    expect(isDateDisabled('2026-08-25', '2026-08-26')).toBe(true)
    expect(isDateDisabled('2026-08-26', '2026-08-26', '2026-08-26')).toBe(false)
    expect(isDateDisabled('2026-08-27', undefined, '2026-08-26')).toBe(true)
  })

  it('builds a Monday-first leap-month calendar', () => {
    const days = calendarMonthDays(new Date(2024, 1, 1))
    expect(days.filter(Boolean)).toHaveLength(29)
    expect(days.find(day => day?.day === 29)?.value).toBe('2024-02-29')
    expect(days.slice(0, 3)).toEqual([null, null, null])
  })
})
