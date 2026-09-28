# 报价单行移动与删除（2026-09-28）

## 实现

- 基于 a491ec8，在 codex/quote-sheet-row-controls 独立工作区实现。
- 当前报价预览操作列增加拖拽手柄、上移、下移、删除；保留原有隐藏。
- 拖拽手柄支持上下方向键；首尾移动按钮禁用；拖拽时显示目标行反馈。
- 删除通过 removeSelection 同步到常用国家、指定渠道、模板三种模式的真实选择，支持不可用渠道及相同渠道的不同国家/分区。
- 排序使用稳定渠道行键，价格、时效、隐藏状态与渠道保持对应。删除或排序不清空其他行手填价；系统重新计价仍执行原有清理逻辑。
- 预览、复制及 capturePrices 使用相同顺序；新报价保存时按 capture 顺序排列 quoteOptions，供记录重新打开使用。历史记录不增加删除或排序按钮。
- 保存、重试和报价计算期间禁用行操作。未修改数据库、历史报价或生产环境。

## 验证

- 六个相关测试文件共 127 项通过：CustomerQuoteSheet、QuotationPreviewRemoval、QuotationCommonMatrix.search、QuotationSystemView.rowsSync、QuotationSystemView.reissue、quotationRecordQuoteSheet。
- vue-tsc -b、目标文件 ESLint、Vite 生产构建和 git diff --check 通过。
- 本地浏览器使用实际 CustomerQuoteSheet 组件与测试数据，实测上移、鼠标拖拽（第三行到第一行）、编辑价格及删除。删除后另一行的 14.80 手填价格保留。
- 截图：outputs/row-controls/verified.png（本地测试数据，非生产截图）。
- 三种模式的真实选择同步通过组件集成测试验证；本次未进行生产登录验收或真实订单保存。

## 交付状态

本地已实现，尚未提交、推送或部署。工作区路径：C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/quote-sheet-row-controls。
