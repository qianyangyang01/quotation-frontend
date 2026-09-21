# 客户报价单 WhatsApp 与列排序（2026-09-21）

## 完成内容

- 按确认的参考图，默认列顺序为：序号、SKU、整组数量价格、国家、物流商、运输时效、处理时间。有临时商品图时，默认在序号后显示图片列。
- 新增可选 WhatsApp 输入；填写后在客户图片标题下方显示，留空则不显示。
- 普通列、SKU、序号和临时商品图均支持拖动排序。价格组使用独立的“价格 · 整组拖动”手柄；组内数量仍可排序、增删和编辑算式。
- 保留左右方向键排序和焦点，复制期间禁用排序；宽表格拖动到边缘可横向滚动。
- 编辑表格、图片预览、文字表格和 Excel/WPS 复制数据遵循同一列顺序。图片列仍只用于图片展示，不混入 TSV 或保存的客户价格。
- 保留原始报价说明。WhatsApp 和列顺序沿用当前报价单临时编辑生命周期，切换商品或离开页面后清除，不增加数据库字段或自动保存真实号码。

## 修改位置

分支：`codex/quote-whatsapp-column-order-20260921`，基线：`962fbf9`。

工作区：`C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/quote-whatsapp-column-order-20260921`

核心文件：

- `src/components/quotation/CustomerQuoteSheet.vue`
- `src/data/customerQuoteSheet.ts`
- `src/services/customerQuoteSheetRenderer.ts`

独立工作区避免覆盖桌面主项目的其他未提交改动。没有修改后端、计价规则、数据库、生产或培训系统。

## 本地验证

- 报价单、剪贴板、价格算式、临时图片与历史记录相关测试：14 个文件，183 项通过。
- 新增检查覆盖整组/组内排序、编辑金额随列移动、保存价格不变、隐藏后恢复位置、跨商品重置、复制期间禁用、移动合并图片单元格。
- 图片几何检查覆盖 1–10 个数量列、16 种隐藏组合、8 种列顺序及有/无商品图，共 2560 种组合。
- 多页渲染验证：每页联系方式、价格与表头位置、移动商品图位置、两行自定义标题和空联系方式。
- `vue-tsc -b`、`vite build`、修改文件 ESLint、`git diff --check` 通过。
- 本地真实浏览器完成普通列鼠标拖动、价格整组鼠标拖动、左右键移动、填写 WhatsApp、真实 PNG 预览；已确认生成图片金额与数量对应。
- 以上为最初本地验证记录；生产发布与 GitHub 状态以发布清单及本次交付结果为准。

本地演示页面（临时测试数据）：`http://127.0.0.1:5191/tmp/quote-layout.html`。

真实浏览器截图：`outputs/quote-whatsapp-layout.png`。截图中的号码为布局占位符，不是预设联系方式。

复核命令：

```text
node node_modules/vitest/vitest.mjs run customerQuoteSheet CustomerQuoteSheet quotationRecordQuoteSheet quoteLocalPhotos quotePriceExpression
node node_modules/vue-tsc/bin/vue-tsc.js -b
node node_modules/vite/bin/vite.js build
```

## 时效文案补充

按用户要求，运输时效与处理时间统一显示 workingdays（无空格），同步预览、文字表格和 TSV。加宽这两列，确保常用时效完整显示。保留原始物流时效数据及说明区正文；实际预览保留用户填写的联系方式，不写入默认配置。本轮 130 项相关测试与类型检查、构建通过，真实浏览器已核对显示。最新截图：outputs/quote-workingdays-layout.png。


## 已授权发布范围

用户已要求完成全部修改后推送 GitHub 并部署生产。新增总成本价 = 当前数量商品成本 + 同数量国内运费，使用 Decimal 运算，明示不含国际运费；左侧总成本，右侧含包材重量。所有本次改动仅涉及前端展示和相关验证，无数据库迁移、后端规则、正式价格或培训系统修改。发布基线 962fbf9（生产 quotation-2026.09.18-07），计划版本 quotation-2026.09.21-01；使用现有预构建镜像发布脚本，切换前备份并保留上一生产版本用于回滚。
