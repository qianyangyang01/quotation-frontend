import { expect, it } from 'vitest'
import { parseQuotePriceInput } from './quotePriceExpression'

it.each([
  ['42.6+2',44.6], ['42.6-2',40.6], ['42.6*1.05',44.73], ['42.6/0.95',44.84],
  ['6/0.95',6.32], ['2+3*4',14], ['(2+3)*4',20], ['10/2/5',1], ['10-2-3',5],
  ['0.1+0.2',0.3], ['1.005*1',1.01], ['1/8',0.13], ['2*-3+10',4],
  ['=（４２.６＋２）÷２',22.3], ['4×.5',2], ['6−2',4], [' 4 + 2 ',6],
  ['42.60',42.6], ['0',0], ['999999999.99',999999999.99], ['',null], ['—',null],
] as const)('calculates %s as %s', (text, value) => {
  expect(parseQuotePriceInput(text)).toEqual({value})
})

it.each(['1/0','1/(2-2)','2+','(2+3','2(3)','2**3','2//3','1 2','1..2','1e3','Infinity',
  'NaN','alert(1)','=SUM(A1:A2)','Math.max(1,2)','1;2','1,000','-1','0-0.001','999999999.99+0.01',
  '1.234','()','=','1'.repeat(121),'('.repeat(18)+'1'+')'.repeat(18), '1234567890123456*0'])('rejects %s', text => {
  expect(parseQuotePriceInput(text).error).toBeTruthy()
  expect(parseQuotePriceInput(text).value).toBeNull()
})
