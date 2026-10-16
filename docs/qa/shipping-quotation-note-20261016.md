# 仅代发货报价备注与搜索联动

## 范围与行为

- 仅 `shipping-only` 模式显示“备注信息（选填）”，最多 200 个 UTF-16 代码单元，与 HTML maxlength 和服务端校验一致。提示说明可填写 SKU、商品名称或其他识别信息，并可按备注关键词查找。
- 独立 `shippingNote` 字段保存；新报价和用户主动撤回修改后的报价，由服务端生成 `仅代发货报价：备注` 的 productSummary。未填写时使用原模式标题。备注不被解析为采购 SKU，不参与成本、重量、运费、佣金或报价计算。
- 我的报价记录和公司报价记录展示完整标题，长文本换行；原有商品检索支持备注，仍与日期、客户、类型、审核状态等筛选共同生效。详情、导出、再次发起、撤回草稿和历史修订同步保留备注。
- 普通报价输入只保存在当前页面；撤回编辑沿用既有 800ms 草稿防抖。模式切换时备注可暂留在当前页面，但仅代发模式才序列化；新报价重置或载入其他模式草稿时清空。
- 新字段与成交跟进 `note` 分离。客户报价单仍使用原模式标签；历史记录不批量补写、重算或重置任何状态。

## 性能与存储

- 不新增数据库表列、索引或 Flyway 迁移。现有 V62 的生成搜索列已包含 productSummary，新建/主动重新保存时由 PostgreSQL 自动维护。
- 页面备注输入不发出额外 API 请求，不使报价矩阵失效，不改变渠道选择或客户价格。
- 报价记录的商品关键词继续走已有 GIN/trigram 索引、250ms 防抖、AbortController 和过期响应保护，未新增逐行 JSON 解析。
- 兼容旧通用 q 检索，将标题加入其现有搜索范围；实际表头检索使用 product 过滤器。

## 本地验证

- 前端全量：251 个测试文件通过、15 个文件跳过；2,079 项通过、17 项跳过。命令 `node node_modules/vitest/vitest.mjs run`。
- `vue-tsc -b`、全量 ESLint、Vite 生产构建、`git diff --check` 通过。
- 后端针对性验证：8 个测试类的最新报告共 29 项通过，0 失败、0 错误、0 跳过。覆盖手填校验、备注校验、草稿、真实 PostgreSQL 检索、保存回读/幂等、撤回重提、统计投影和索引计划。
- PostgreSQL 检索测试覆盖中英文、大小写以及 `%`、`_`、斜杠字面量、105 条跨页结果、筛选总数；查询前后已存报价和审核快照 hash 不变。
- 性能样本为本地 3,100 条记录，每条含 60 个渠道大快照。批量插入后自然选择顺序扫描时执行 1.239ms；正常 `VACUUM ANALYZE` 后自然选择 `Bitmap Index Scan on quotation_record_product_search_idx`，命中 1 条，Execution 0.757ms / Planning 2.342ms，精确读取 1 个 heap block、31 个 shared buffers。未禁用顺序扫描或强制 planner。该指标仅为本地代表性 SKU 关键词的数据库查询，不是生产页面端到端耗时，也不保证所有短关键词均采用索引。
- 新增测试覆盖输入 30 次后的零额外请求、稳定计算上下文/渠道/价格，模式隔离、空备注、200 字边界、保存回读、再次发起、撤回、纯文本展示、搜索防抖和过期请求、筛选导出、历史修订。
- 浏览器本地示例组件在 1280px 和 800px 宽度检查：输入对齐，长标题在自身列内换行，无新增横向溢出。预览页拦截全部业务 API，禁止写操作，没有连接生产业务数据。
- 本地截图：`artifacts/shipping-note-preview/desktop.jpg`；可复现预览：启动本工作区 Vite 并访问 `/artifacts/shipping-note-preview/index.html`。预览文件仅保留本地，不属于发布代码。
- 本次 Vite 预览服务、预览浏览器页和测试 Maven/PostgreSQL 容器已结束；未删除其他任务资源。

## 交付边界

本次完成本地实现和验证；没有向生产保存测试报价、修改历史数据或执行部署。分支 `codex/shipping-quotation-note` 基于 `52ab2c25d9985f8cc19da78cf2e445370f0a01e7`。正式上线前需按当时最新生产版本合入并完成发布检查。

## 后端复核

实际验证分三轮执行，最新 Surefire 报告保存在 `backend/target/surefire-reports`。首轮 27 项通过；第二轮 10 项通过、1 项因刚批量插入后的 GIN pending list 状态未选索引而失败；补正常 VACUUM ANALYZE 后第三轮仅重跑该性能测试并通过。未更改业务查询以迎合执行计划断言。

最后一轮实际命令：

```powershell
docker run --rm --name quotation-shipping-note-index-test -v 'C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/shipping-quotation-note/backend:/workspace' -v 'C:/Users/25490/.m2:/root/.m2' -v '/var/run/docker.sock:/var/run/docker.sock' -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B '-Djacoco.destFile=/tmp/shipping-note-jacoco.exec' '-Dtest=QuotationProductSearchPostgresTest#shippingNoteSearchUsesExistingTrigramIndexWithoutReadingEveryPricingSnapshot' test
```

其他已执行测试：`ShippingQuotationNoteTest`、`ManualQuotationTest`、`QuotationDraftControllerTest`、`QuotationRecordColumnFiltersPostgresIntegrationTest`、`QuotationAnalyticsPostgresIntegrationTest`、`QuotationWorkflowIntegrationTest#savesAndReadsBackBothManualModesWithoutPurchaseSnapshots`、`QuotationWithdrawalPostgresTest#shippingNoteSurvivesWithdrawalResubmissionAndIndependentReissueWithOriginalSnapshotRetained`。JaCoCo 输出放容器 `/tmp` 避免 Windows bind mount 上 coverage 文件写入造成测试进程退出缓慢；不影响业务性能测量。
