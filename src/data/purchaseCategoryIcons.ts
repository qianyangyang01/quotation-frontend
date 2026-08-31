// All category marks share a 24 × 24 grid and a consistent outline weight.
const icons = {
  shirt: ['M8 3 3 6l-2 5 5 2v8h12v-8l5-2-2-5-5-3', 'M8 3c0 4 8 4 8 0'],
  paw: ['M8 13c-1 2-4 3-4 6 0 3 5 2 8 1 3 1 8 2 8-1 0-3-3-4-4-6-2-3-6-3-8 0', 'M7 4c-3-2-4 5-1 6s4-5 1-6', 'M12 2c-3 0-3 7 0 7s3-7 0-7', 'M18 4c-3-1-4 5-1 6s4-5 1-6'],
  capsule: ['M8 3a5 5 0 0 1 7 7l-5 5a5 5 0 0 1-7-7Z', 'm6 5 7 7', 'M12 21c6 0 9-4 9-9-5 0-9 3-9 9Z', 'm12 21 6-6'],
  bra: ['M4 3v5m16-5v5', 'M2 8h8l2 4 2-4h8l-1 7c-1 4-7 4-9 0-2 4-8 4-9 0Z'],
  socks: ['M5 3h7v10l-5 7c-3 3-7-1-4-4l2-2Z', 'M16 3h5v10l-5 7c-1 1-3 1-4 0', 'M5 7h7m4 0h5'],
  briefs: ['M3 5h18l-4 14H7Z', 'M3 9h18', 'M4 10c4 0 6 3 6 9m10-9c-4 0-6 3-6 9'],
  lipstick: ['M8 12h8v9H8Z', 'M9 12V5l6-3v10', 'M8 16h8'],
  bottle: ['M9 3h6v4H9Z', 'M9 7c-1 2-3 2-3 5v8c0 1 1 1 2 1h8c1 0 2 0 2-1v-8c0-3-2-3-3-5', 'M6 13h12'],
  garden: ['M12 21V9', 'M12 14C4 14 3 9 3 5c7 0 9 4 9 9Z', 'M12 10c0-5 4-7 9-7 0 5-3 8-9 8'],
  appliance: ['M5 2h14v20H5Z', 'M5 8h14', 'M8 5h1m3 0h4', 'M8 15a4 4 0 1 0 8 0 4 4 0 1 0-8 0'],
  dumbbell: ['M6 9h12v6H6Z', 'M3 6h3v12H3Zm15 0h3v12h-3Z', 'M1 10v4m22-4v4'],
  kitchen: ['M4 3v5c0 4 6 4 6 0V3M7 3v18', 'M19 3c-4 1-5 5-5 9h5m0-9v18'],
  bed: ['M3 7v14m18-14v14M3 17h18', 'M3 12h18v5H3Zm2-5h5v5H5Zm9 0h5v5h-5Z'],
  gem: ['M3 8 7 3h10l4 5-9 13Z', 'M3 8h18M7 3l5 18 5-18'],
  shoe: ['M3 9v10h18v-4l-7-3-4-7c-1 4-4 5-7 4Z', 'M3 16h18m-9-7-3 2m6 0-3 2'],
  pencil: ['m4 16-1 5 5-1L21 7l-4-4Z', 'm14 6 4 4M4 16l4 4'],
  lamp: ['M8 3h8l5 11H3Z', 'M12 14v7m-5 0h10'],
  phone: ['M7 2h10c1 0 2 1 2 2v16c0 1-1 2-2 2H7c-1 0-2-1-2-2V4c0-1 1-2 2-2Z', 'M10 5h4m-3 14h2'],
  spool: ['M6 3h12M6 21h12M8 3v18m8-18v18', 'm8 7 8 3m-8 1 8 3m-8 1 8 3'],
  toy: ['M8 4a4 4 0 1 0-6 5l1 11h18l1-11a4 4 0 1 0-6-5Z', 'M8 12h.01M16 12h.01M9 16c2 2 4 2 6 0'],
  book: ['M12 5C8 2 4 3 2 4v16c3-1 7-1 10 1 3-2 7-2 10-1V4c-2-1-6-2-10 1Z', 'M12 5v16'],
  medical: ['M9 3h6v6h6v6h-6v6H9v-6H3V9h6Z'],
  car: ['M3 11 5 5h14l2 6v8H3Z', 'M3 11h18M6 15h2m8 0h2M5 19v2m14-2v2'],
  clean: ['M8 3h10v3h-4v4l4 4v7H6v-7l4-4V6H8Z', 'M18 4h3m-5 4 3 3M6 16h12'],
  bag: ['M4 7h16l1 14H3Z', 'M8 9V6a4 4 0 0 1 8 0v3'],
  skincare: ['M9 3h6M12 3v4M7 7h10v14H7Z', 'M9 14h6'],
  box: ['m3 7 9-4 9 4v10l-9 4-9-4Z', 'm3 7 9 4 9-4M12 11v10M7 5l9 4'],
} satisfies Record<string, string[]>

export const purchaseCategoryIconNames: Record<string, keyof typeof icons> = {
  文胸:'bra', 袜子:'socks', 内裤:'briefs', 服装:'shirt', 化妆品:'lipstick', 保健品:'capsule',
  日用品:'bottle', 庭院工具:'garden', 家用电器:'appliance', 健身器材:'dumbbell', 厨房用具:'kitchen',
  家纺:'bed', 配饰:'gem', 鞋:'shoe', 文具:'pencil', 灯具:'lamp', 数码:'phone', 辅料:'spool',
  玩具:'toy', 书籍:'book', 宠物用品:'paw', 医疗:'medical', 汽车用品:'car', 清洁用品:'clean',
  箱包:'bag', 护肤品:'skincare', 其他:'box',
}
export function purchaseCategoryIcon(category: string): string[] {
  return icons[purchaseCategoryIconNames[category.trim()] ?? 'box']
}
