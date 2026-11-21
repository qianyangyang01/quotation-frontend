import { expect, it } from 'vitest'
import { pinUsAverageRows, placeAverageRowsBeforeMembers } from './quoteSheetRowOrder'

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

it('pins US summaries only within contiguous SKU blocks without changing prices or regrouping repeated SKUs',()=>{
 const rows=[['a','A'],['us-a','A'],['b','B'],['us-b','B'],['a2','A'],['us-a2','A']].map(([key,sku],i)=>({key:key!,sku:sku!,price:i+1}))
 const before=JSON.stringify(rows),pinned=new Set(['us-a','us-b','us-a2'])
 const output=pinUsAverageRows(rows,pinned)
 expect(output.map(row=>row.key)).toEqual(['us-a','a','us-b','b','us-a2','a2'])
 expect(output.map(row=>row.price)).toEqual([2,1,4,3,6,5])
 expect(pinUsAverageRows(output,pinned)).toEqual(output)
 expect(JSON.stringify(rows)).toBe(before)
})
