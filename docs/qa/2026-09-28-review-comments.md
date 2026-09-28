# 审核意见（选填）与员工可见性

实现工作区：`C:\Users\25490\Documents\ProjectWorkspaces\quotation\worktrees\review-comments`。
分支：`codex/review-comments`，基线：`a491ec8`。主目录既有未提交内容未改动。

## 行为

- 意见填写入口放在报价详情内；完成审核时可在结果弹窗中填写并一起提交，不需要退出详情重新找记录。列表仅展示最新意见摘要、填写人和时间，以及只读「查看审核意见」入口。
- 完成审核弹窗显示当前 SKU、客户及报价单号，意见为选填，审核通过、价格异常、同渠道免审均允许空意见。
- 财务／超级管理员可以单独追加意见，不要求先领取审核。追加意见不改变占用、审核结论、报价内容、报价版本、更新时间或成交结果。
- 员工在有权查看的记录列表及详情中看到相同摘要，可打开完整历史；无编辑入口，后端禁止员工写入及查看他人记录。
- 意见追加保存在已有 `quotation_review.state.history`，保留历史意见及历史审核备注；列表和轮询只返回意见数量与最新意见。无需数据库迁移或历史数据重算。
- 继续使用报价版本、审核版本和记录行锁；失败保留输入，后台无变化刷新不关闭弹窗；记录变化时要求重新打开核对。
- 列表自动刷新或新报价插入时保持当前详情的记录身份；完成审核后留在当前详情。

## 本地验证

- 前端专项：12 个文件，86 项通过，覆盖记录列表、详情、审核、员工同步、生命周期、意见输入及失败恢复；包含新报价插入、其他记录归档时保持当前审核详情，以及完成后仍停留当前详情。
- 变更的前端文件 ESLint 通过；`vue-tsc -b` 与 Vite 正式构建通过；`git diff --check` 通过。
- H2 Spring API 集成：`QuotationFinanceReviewIntegrationTest` 17 项通过。
- PostgreSQL Testcontainers：`QuotationReviewPostgresIntegrationTest` 与 `QuotationRecordQueryPostgresIntegrationTest` 共 18 项通过，无跳过。
- 另增 PostgreSQL 搜索专项 1 项通过：同一记录的员工与财务列表返回相同意见、填写人、时间；无权限员工搜索结果为空。执行记录：`backend/review-search-tests.log`。
- 浏览器使用真实 Vue 组件和本地模拟 API，验证输入、保存、历史追加、列表摘要和员工只读界面。发现并修复列表刷新触发弹窗关闭的问题，补充无变化轮询保留输入测试。
- 测试页面：`tmp/review-comments-preview.html`；最终填写位置截图：`outputs/review-comments/review-result-inside.png`；员工只读截图：`outputs/review-comments/employee-readonly.png`。均为本地测试数据，不是生产用户验收。

## 发布状态

本次仅完成本地实现与验证，尚未提交、推送或部署。后续发布需前后端同步上线，并执行生产审核人／员工同记录验收。
