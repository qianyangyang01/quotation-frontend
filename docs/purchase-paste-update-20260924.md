# 采购粘贴同 SKU 更新

## 交付范围

- 独立分支：`codex/purchase-paste-update-20260924`。
- 工作区：`C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/purchase-paste-update-20260924`。
- 基线：`2f016165c734c354ca9879193e21f968dcf69291`，依据本地 `quotation-2026.09.24-14/verification.md` 发布验收记录选择；本次没有读取或修改生产数据库，也未确认当前线上仍为该版本。
- 仅实现和本地验证；未提交、未推送、未部署。原桌面工作区及其他任务的修改未改动。无数据库迁移。

## 行为与接口

弹窗调整为“采购粘贴新增/更新”。已有 SKU 可仅填写 SKU 和变更字段；空白、null 保留旧值，明确的 0 和 0% 会更新。新 SKU 仍须满足原有必填要求。大小写和空白规范化、同批重复保留第一条、100 行限制继续保留，并显示重复行号。

新增 `POST /api/v1/purchase-products/paste/preview`，接收含 sourceRow 的稀疏字段数组。服务端合并当前商品资料，校验关联字段，返回 create/update/unchanged 分类、字段差异、最终资料及预期商品 ID/版本/更新时间。前端显示新旧值并统一确认。取消返回编辑不丢输入。

新增 `POST /api/v1/purchase-products/paste/confirm`，接收 `{ rows, expected }`。按 SKU 顺序锁定已有商品，检查 ID、版本、更新时间和预期不存在条件；整批重新合并校验后，在一个事务内保存商品及逐商品修改记录。已有修改、删除重建或新增竞争返回冲突，不覆盖后来者。唯一约束保护同时创建同 SKU 的最后竞争窗口。旧 `POST /paste` 接口仍只新增、跳过已有 SKU。

只允许修改粘贴列的业务字段；保留图片、产品 UUID、目录状态、来源表和来源标识。旧数据仍使用原含票价优先规则，预览和保存共享换算逻辑；填写基准价与最终价不同时明确提醒核对含票价。旧数据国内运费继续采用其既有 1 件运费口径。

每次真实更新沿用商品“修改记录”，操作为“采购粘贴更新”，由服务器记录登录账号、姓名、时间和逐字段前后值。无变化不写商品、不增加版本或修改记录。异常/超时保留输入并要求重新预览；不会自动重试覆盖。

保存结果分别返回新增、更新、无变化和重复跳过；复制 SKU/品类仅包含后端确认成功的新增和更新商品。采购列表收到保存结果后刷新。没有增加任何历史报价重算、审核重置或报价状态更新路径。

## 验证结果

- 前端 7 个文件共 58 项通过：PurchasePasteDialog、purchasePaste、purchasePastePatch、PurchaseDataWorkspace、PurchaseHistoryDialog、purchaseStore、purchaseClipboard。
- 后端 4 个测试类共 39 项通过，零失败、零跳过：PurchasePastePostgresIntegrationTest 9 项、PurchaseProductServiceTest 20 项、PurchaseProductControllerTest 4 项、PurchaseHistoryIntegrationTest 6 项。
- PostgreSQL 16.4 使用 Testcontainers 隔离实例，执行实际 Flyway 迁移；覆盖稀疏更新、图片/来源/目录状态/UUID 保留、服务器操作者及差异记录、零值、同批重复、无变化重复提交、阶梯合并校验、新增必填、旧数据最终价、旧版本/新增竞争/删除重建冲突、事务回滚、真实双线程确认竞争、权限、100 行边界、旧接口兼容，以及历史报价整行读回完全一致。
- `pnpm build`（含 vue-tsc）通过；修改范围 ESLint 和 `git diff --check` 通过。
- 本地浏览器实际渲染检查：原价 12.5 → 15、票点 8% → 0% 的对比清晰；返回编辑保留输入；确认后成功提示与复制按钮正常。此项使用本地示例响应，仅验证界面；保存及审计真实性由上述 PostgreSQL 测试验证，未作生产角色会话验收。

## 后续集成

前后端需一起集成。发布前重新核对最新生产基线并合并并行任务；沿用项目的版本发布和角色验收流程。本工作区测试日志为 `backend/paste-tests.log`、`build-paste.log`，测试 XML 位于 `backend/target/surefire-reports`。
