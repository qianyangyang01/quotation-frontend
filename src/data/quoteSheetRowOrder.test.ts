import { expect, it } from 'vitest'
import { placeAverageRowsBeforeMembers } from './quoteSheetRowOrder'

it('places each summary before its own first source without reordering countries or source routes', () => {
  const order = ['us-b', 'us-a', 'avg-us', 'it-a', 'it-b', 'avg-it', 'other-sku']
  const before = [...order]
  expect(placeAverageRowsBeforeMembers(order, [
    { key: 'avg-it', members: ['it-a', 'it-b'] },
    { key: 'avg-us', members: ['us-a', 'us-b'] },
  ])).toEqual(['avg-us', 'us-b', 'us-a', 'avg-it', 'it-a', 'it-b', 'other-sku'])
  expect(order).toEqual(before)
})

it('adds multiple summaries at the same anchor once and retains summaries whose sources are hidden', () => {
  const plans = [{ key: 'avg-a', members: ['a', 'b'] }, { key: 'avg-b', members: ['a', 'b'] }, { key: 'hidden', members: ['c', 'd'] }]
  const result = placeAverageRowsBeforeMembers(['hidden', 'a', 'b', 'avg-a'], plans)
  expect(result).toEqual(['hidden', 'avg-a', 'avg-b', 'a', 'b'])
  expect(placeAverageRowsBeforeMembers(result, plans)).toEqual(result)
})
