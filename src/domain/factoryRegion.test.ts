import { describe, expect, it } from 'vitest'
import { resolveFactoryRegion } from './factoryRegion'

describe('conservative factory region hints', () => {
  it.each([
    ['浙江义乌某某有限公司', '浙江省', '金华市', 1.5],
    ['沭阳县美羡仙花木园艺场', '江苏省', '宿迁市', 1.5],
    ['常熟某某有限公司', '江苏省', '苏州市', 1.5],
    ['海宁市某某有限公司', '浙江省', '嘉兴市', 1.5],
    ['诸暨市某某有限公司', '浙江省', '绍兴市', 1.5],
    ['安徽某某公司', '安徽省', undefined, 1.5],
    ['上海汇彩数码印花有限公司', '上海市', '上海市', 1.5],
    ['君樾国际科技(广州)有限公司', '广东省', '广州市', 3],
    ['深圳西之月选品跨境供应链有限公司', '广东省', '深圳市', 3],
    ['北京市朝阳区某某公司', '北京市', '北京市', 3],
    ['长春市朝阳区某某公司', '吉林省', '长春市', 3],
    ['即墨市韩尚服装厂', '山东省', '青岛市', 3],
    ['海门区富水罗家纺厂', '江苏省', '南通市', 1.5],
    ['南通迪斯特纺织品有限公司', '江苏省', '南通市', 1.5],
    ['湖南贝莘萘丝工贸有限公司', '湖南省', undefined, 3],
    ['河北美潮美家供应链管理有限公司', '河北省', undefined, 3],
    ['南昌霖薇包装有限公司', '江西省', '南昌市', 3],
    ['深圳市新龙祥创新实业有限公司', '广东省', '深圳市', 3],
    ['东莞市长安鑫鸿凯包装厂', '广东省', '东莞市', 3],
    ['潮州市潮安区沙溪镇环图日用品厂', '广东省', '潮州市', 3],
    ['天津市河北某某公司', '天津市', '天津市', 3],
    ['天门市伊人服饰', '湖北省', '天门市', 3],
    ['中国·浙江义乌某某商行', '浙江省', '金华市', 1.5],
    ['工厂地址：江苏苏州某某工厂', '江苏省', '苏州市', 1.5],
    ['香港某某公司', '香港特别行政区', undefined, 3],
  ])('recognizes %s conservatively', (factory, province, city, continuationRateCny) => {
    expect(resolveFactoryRegion(String(factory))).toMatchObject({ province, continuationRateCny })
    expect(resolveFactoryRegion(String(factory)).city).toBe(city)
  })

  it.each(['', '微信供应商', '朝阳区某某公司', '江苏省广州市某某公司', '某某苏州电子有限公司',
    '某某义乌电子有限公司', '莲花生物科技公司', '广州某某公司\n义乌某某公司', '义乌某某商行；工厂地址：广东广州',
    '北京市某某公司（上海）'])('keeps ambiguous or unreliable %s unresolved', factory => {
    expect(resolveFactoryRegion(factory)).toEqual({ regionLabel: '地区待核实' })
  })

  it('does not retain a previous lookup and tolerates malformed optional text', () => {
    expect(resolveFactoryRegion('义乌市某某商行').continuationRateCny).toBe(1.5)
    expect(resolveFactoryRegion('暂无工厂信息').continuationRateCny).toBeUndefined()
    expect(resolveFactoryRegion(null as unknown as string).continuationRateCny).toBeUndefined()
  })
})
