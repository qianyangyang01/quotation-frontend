import { decimal, QuoteDecimal } from '@/services/quotationDecimal'

export const MAX_PRICE_EXPRESSION_LENGTH = 120
type PriceInputResult = { value: number | null; error?: never } | { value: null; error: string }

/** A bounded arithmetic grammar. No JavaScript, names, spreadsheet references or implicit multiplication. */
export function parseQuotePriceInput(input: string): PriceInputResult {
  const raw = input.trim()
  if (!raw || raw === '—') return { value: null }
  if (raw.length > MAX_PRICE_EXPRESSION_LENGTH) return { value: null, error: '算式最多 120 个字符' }
  const source = raw.normalize('NFKC').replace(/×/g, '*').replace(/÷/g, '/').replace(/−/g, '-').replace(/^=\s*/, '')
  // Preserve the existing rule for a directly entered amount; arithmetic results are rounded once at the end.
  if (/^\d+(?:\.\d*)?$/.test(source) && !/^\d+(?:\.\d{1,2})?$/.test(source)) {
    return { value: null, error: '直接填写金额最多两位小数' }
  }
  if (!source || /[^\d.\s+*/()-]/.test(source)) return { value: null, error: '仅支持数字、加减乘除和括号' }
  let position = 0
  const skip = () => { while (/\s/.test(source[position] ?? '') && position < source.length) position++ }
  function factor(depth: number): InstanceType<typeof QuoteDecimal> {
    if (depth > 16) throw new Error('算式嵌套过多，请简化')
    skip()
    const token = source[position]
    if (token === '+' || token === '-') {
      position++
      const value = factor(depth + 1)
      return token === '-' ? value.negated() : value
    }
    if (token === '(') {
      position++
      const value = sum(depth + 1)
      skip()
      if (source[position++] !== ')') throw new Error('括号不匹配')
      return value
    }
    const number = /^(?:\d+(?:\.\d+)?|\.\d+)/.exec(source.slice(position))?.[0]
    if (!number) throw new Error('算式不完整，请检查数字和运算符')
    if (number.replace('.', '').length > 15) throw new Error('单个数字最多 15 位，请简化算式')
    position += number.length
    return decimal(number)
  }
  function product(depth: number): InstanceType<typeof QuoteDecimal> {
    let value = factor(depth)
    for (;;) {
      skip()
      const op = source[position]
      if (op !== '*' && op !== '/') return value
      position++
      const right = factor(depth)
      if (op === '/' && right.isZero()) throw new Error('除数不能为 0')
      value = op === '*' ? value.times(right) : value.div(right)
    }
  }
  function sum(depth: number): InstanceType<typeof QuoteDecimal> {
    let value = product(depth)
    for (;;) {
      skip()
      const op = source[position]
      if (op !== '+' && op !== '-') return value
      position++
      const right = product(depth)
      value = op === '+' ? value.plus(right) : value.minus(right)
    }
  }
  try {
    const value = sum(0)
    skip()
    if (position !== source.length) throw new Error('算式格式不正确，请检查运算符和括号')
    if (!value.isFinite() || value.isNegative() && !value.isZero()) throw new Error('价格须为非负美元金额')
    if (value.gt('999999999.99')) throw new Error('价格不能超过 999999999.99')
    return { value: value.toDecimalPlaces(2, QuoteDecimal.ROUND_HALF_UP).toNumber() }
  } catch (error) {
    return { value: null, error: error instanceof Error ? error.message : '算式无法计算' }
  }
}
