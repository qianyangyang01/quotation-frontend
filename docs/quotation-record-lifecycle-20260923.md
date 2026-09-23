# 报价记录归档与回收站

## 交付范围

基于发布提交 10a8eca6151abd80c95509d0a4f2b6c865b7204c 的独立分支
`codex/quotation-record-lifecycle-20260923`。
保留现有橙色导航、统计卡片、筛选、明细和分页，新增当前记录 / 已归档 / 回收站、
全选本页、批量归档、移入回收站及恢复确认。界面确认显示报价单号、客户、SKU、
分类/恢复目标，要求填写原因。

- 超级管理员且有公司记录权限：可处理公司记录。
- 其他有记录权限的用户：只能处理自己的记录；不能恢复管理员替其清理的记录。
- 已成交、存在成交明细/数量、财务已审核记录，归档和清理均拦截，包括管理员的普通批量操作。
- 归档保留历史业务统计。回收站排除经营统计；原始报价快照保留。
- 回收站恢复到移入前分类；归档恢复到当前记录。
- 归档/回收站记录可查看，修改及财务审核必须先恢复。
- 无永久删除、自动清理或按名称自动认定测试记录。

## 数据与接口

V51 新增 quotation_record.lifecycle_state，默认 active，允许 active/archived/trashed。
不改旧报价 payload、原始金额、成交状态或审核状态；迁移仅增加列、约束、索引。

POST /api/v1/quotations/lifecycle：action 为 archive/trash/restore，
reason 必填 1–200 字，items 为 id/version 列表，每批最多 100 条，禁止重复 ID。
按 UUID 顺序锁定报价行，先检查整批权限、版本及保护状态，然后事务提交及逐条审计。
任何异常整批回滚。重复提交旧版本返回冲突，不重复生成审计。
与报价修改、审核写入共用 records.lockById。

GET /quotations/search 默认只查 active；lifecycle 可指定 archived/trashed。
分页总数、筛选摘要、国家选项、筛选导出均使用同一分类。
GET /quotations 供概览统计使用，返回 active + archived，排除 trashed。
GET /quotations/{id} 保留原权限，可查看非当前记录。
审核轮询包含 lifecycleState，另一页面改变分类后当前列表重新加载。
生命周期元数据由服务端填写，创建报价时清除客户端同名字段。
清理时间、操作人、原因及每次分类变更写入记录与审计。

## 验证

前端全量：167 文件通过、9 文件跳过；1138 测试通过、11 跳过（现有语料跳过项）。
类型检查、生产构建通过。新增界面测试覆盖确认/原因、保护、筛选后清空选择、
旧版本错误保留、恢复目标与角色边界。

后端相关 7 个测试类共 25 项全部通过，0 失败、0 错误、0 跳过。
验证包含：生命周期接口（真实 Spring Security、JPA、事务与审计），
财务审核既有回归，查询权限，真实 PostgreSQL 分类查询与 V51 原始 payload 保留，
完整 Flyway 升级链及物流/财务/历史报价数据不变检查。
迁移从旧版本升级至 V51 通过，新增迁移不改变既有业务快照。
曾因迁移编号协调时 target/classes 残留 V50 产物导致重复列错误；
清除唯一旧产物后复验通过，源码与最终构建资源均只有 V51。

本地浏览器预览使用实际构建产物和仅驻留内存的演示 API，账号标明“本地演示 · 非生产”。
已检查全选跳过已成交/已审核、清理确认、列表刷新、回收站元数据及恢复。
该浏览器验证不等同于生产验收；真实后端的权限/事务/迁移由集成测试验证。

## 并行任务合并与发布边界

采购审计占 V49，财务审核占用占 V50，本任务占 V51。尚未发布。
不得直接用本分支覆盖较新生产版本。发布前应合并最新生产提交，核对迁移资源，
按最终提交重新验证；只新增功能，不自动清理现有生产记录。

财务审核占用分支改变审核状态来源为 quotation_review 并独立维护审核版本：
本工作区已三方合入财务功能，protectedRecord 使用 QuotationReviewService.currentStatus(row)，
包括 reviewing 状态；其 claim/cancel/release/complete 入口在持有报价行锁后执行
QuotationLifecycleController.assertActive(row)。恢复不恢复旧审核占用。
保留本任务生命周期查询条件与审核分支的状态 overlay，以及前端审核独立组件。
联合测试及最终交付状态见 `finance-review-claim-20260923.md`；归档原独立工作区未被修改。

后续发布共同锁：/srv/ahmln-data/quotation-app/maintenance/release.lock
及 /tmp/quotation-production-release.lock；在锁内重新检查 current 与 SHA。
本任务未切换生产容器，未写入或清理生产业务数据，未涉及培训系统。
