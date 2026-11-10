// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { loadCountrySearchHistory, saveCountrySearchHistory, recordCountrySearch, rankCountrySearchHistory } from './countrySearchHistory'

afterEach(() => { localStorage.clear(); vi.restoreAllMocks() })

it('ranks by frequency then recency and merges the same country code', () => {
  let history = recordCountrySearch([], 'jp', 1)
  history = recordCountrySearch(history, 'JP', 2)
  history = recordCountrySearch(history, 'DE', 3)
  history = recordCountrySearch(history, 'FR', 4)
  expect(rankCountrySearchHistory(history)).toEqual([
    { code: 'JP', count: 2, lastSearchedAt: 2 },
    { code: 'FR', count: 1, lastSearchedAt: 4 },
    { code: 'DE', count: 1, lastSearchedAt: 3 },
  ])
})

it('persists per account and clears only that account', () => {
  const history = recordCountrySearch([], 'JP')
  saveCountrySearchHistory('a', history)
  saveCountrySearchHistory('b', history)
  expect(loadCountrySearchHistory('a')).toEqual(history)
  saveCountrySearchHistory('a', [])
  expect(loadCountrySearchHistory('a')).toEqual([])
  expect(loadCountrySearchHistory('b')).toEqual(history)
  saveCountrySearchHistory('', history)
  expect(loadCountrySearchHistory('')).toEqual([])
})

it('ignores corrupt data, invalid entries and duplicate codes', () => {
  const key = 'milano.country-search-history.v1:a'
  localStorage.setItem(key, '{broken')
  expect(loadCountrySearchHistory('a')).toEqual([])
  localStorage.setItem(key, JSON.stringify([null, { code: 'JP', count: -1, lastSearchedAt: 0 },
    { code: 'JP', count: 2, lastSearchedAt: 3 }, { code: 'JP', count: 1, lastSearchedAt: 4 },
    { code: '云途', count: 1, lastSearchedAt: 4 }]))
  expect(loadCountrySearchHistory('a')).toEqual([{ code: 'JP', count: 2, lastSearchedAt: 3 }])
})

it('does not break search when browser storage is blocked', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  expect(loadCountrySearchHistory('a')).toEqual([])
  expect(() => saveCountrySearchHistory('a', recordCountrySearch([], 'JP'))).not.toThrow()
})
