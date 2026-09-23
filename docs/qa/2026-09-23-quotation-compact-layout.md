# 报价页紧凑布局本地验收

## 范围

- 初版基线：68d6551。独立分支 codex/quotation-compact-layout-20260923。按用户确认的 B 版布局完成第二轮调整，已通过 5f1d206 合入其他任务最新提交 10a8eca，包含模板明细 8a66c83 与物流标题修复。
- 新增 src/styles/quotationCompact.css；QuotationSystemView.vue 引入样式，并增加矩阵和报价预览的外层布局容器；QuotationCommonMatrix 和 QuotationPreviewSave 只移动模板节点、增加布局容器及调整标题。
- 不修改组件脚本、props、事件、价格计算、数据读取、草稿、保存、模板逻辑或后端。
- 主目录和其他任务工作区未写入本次改动。后续发布仍需确认届时最新生产提交；当前无合并冲突。

## 布局

- 大于 1100px：六项报价条件同排；佣金阈值灰底，继续可编辑；成本字段和摘要紧凑排列。
- 大于等于 1280px：商品信息与成本并排；国家选择位于渠道表左侧，右侧为本次报价摘要和原有保存按钮；客户报价单编辑区整行放在下方。
- 国家列表两列独立滚动，完整保留搜索、拖动排序、加载状态和错误重试。矩阵、摘要、编辑区使用共享网格；预览根元素保留实际布局尺寸，以维持原有校验提示 scrollIntoView 定位。
- 沿用系统白底、浅灰背景、橙色保存按钮及自定义价格。报价方式、常用国家区域、选中国家/渠道使用绿色；佣金阈值灰底；保留错误/警示样式。
- 手机使用原有单列布局。表格沿用横向滚动；不缩小生成报价图片。

## 检查结果

- 97 项既有测试通过：QuotationInteraction、QuotationCommonMatrix.search、CostWeightPanel.precision、QuotationSystemView.flow、QuotationSystemView.previewRef、QuotationSystemView.photoReset、QuotationTemplateAcceptance、QuotationTemplateStatusAcceptance。包括真实保存按钮捕获各模式中编辑后的价格。
- vue-tsc -b 通过。
- 三个修改的 Vue 文件 ESLint、git diff --check、最终 Vite build 通过。
- 对比 10a8eca：三个修改组件的 script setup 完全一致；Vue 模板 AST 的指令、事件、动态表达式、插值和组件 ref 集合完全一致。仅改变布局和静态标题。
- 浏览器使用真实 Vue 组件和本地模拟数据，未登录或操作生产。浏览器 error 日志为空。
- 初版已检查客户下拉、查询事件、佣金输入、渠道搜索和模式切换。B 版另行检查国家搜索/清空、美国与英国跨国加入（2 国 2 渠道）、移除后恢复 1 国 1 渠道、手工重量和计抛完整展示；客户报价单输入 26.3+2 后回车得到 28.30。浏览器验收不调用正式保存接口。
- 390px 窄屏无整页横向溢出，条件单列。1366px 和 1920px 的六项条件在同一行，无整页横向溢出。

## 高度对照和限制

固定场景：单品、28 个常用国家、15 条候选渠道、每页 5 条、加入 1 条报价、未计抛。测试页含 48px 模拟工具条；真实应用导航和提示可能增加高度。

| 浏览器视口 | 原版整页高度 | 新版整页高度 |
| --- | ---: | ---: |
| 1920 × 1080 | 4297px | 2088px |
| 1366 × 768 | 未测 | 2196px |

1080 高度下约两屏，768 高度下仍接近三屏。更多报价行、组合商品、计抛、校验提示或大字号会增加高度；不能声称所有电脑/所有状态严格两屏。

验收页面和截图在本工作区 .codex-tmp/compact-preview/，属于本地辅助证据，不随代码提交。layout-b-desktop.png 为最终 B 版桌面截图；desktop-before.png 为初始参照，desktop-after.png 为第一轮紧凑方案。create.mjs 用真实组件生成模拟页，verify-ui-only.mjs 比较脚本和模板动态绑定。

当前仅本地完成，没有 GitHub 推送、生产部署或生产登录验收。
