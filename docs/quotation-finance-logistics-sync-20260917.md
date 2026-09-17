# 员工报价与财务、物流配置一致性修复

发布候选：基于线上 quotation-2026.09.17-02 / d312266 建立独立工作区，计划发布 quotation-2026.09.17-03。实际提交、部署与验收结果以发布包 evidence 和生产 manifest 为准。

## 原因与调整

- 首次挂载报价页时，客户等级和汇率会先读取默认值。原初始化在服务器财务配置加载后只回填部分配置，遗漏等级和汇率；现在首次进入、恢复草稿之前统一调用完整财务配置应用逻辑。
- 报价页每次进入均重新读取服务器财务配置，避免已有内存缓存过期。财务 GET 明确禁止浏览器缓存。
- 物流属性选项改为响应式计算，财务渠道配置加载或更新后新增属性能够同步出现。
- `/quotation-sync` 增加财务七类设置的版本号。只查询键和版本，不读取配置内容，沿用原报价/财务访问权限和已发布物流版本。
- 报价页记录实际已应用的版本，后台核验国家分类、物流渠道权限、客户等级、汇率、税费、附加费、客户操作费。保存前另行读取完整财务设置，防止后台轮询间隙的变化漏检。
- 空白报价自动刷新；已有商品的报价提示变更，更新重算后方可保存。旧服务器没有版本字段时，回退到完整财务读取校验。
- 已停用的客户等级不继续作为默认选项；恢复草稿在完整配置加载后处理。

## 物流核查

继续沿用已发布物流版本和选中渠道校验。版本覆盖正式库、物流商/渠道状态、发布版本、价格指纹、计费验收与公司物流状态。未发布准备数据不作为报价规则。

已运行物流缓存和同步异常回归，覆盖版本刷新、缓存过期、旧响应隔离、渠道价格/可用性变化导致的 409/422、网络失败，以及后台加载不得覆盖更新的保存核验。

## 本次修改文件

- `src/views/QuotationSystemView.vue`
- `src/services/financeSettings.ts`
- `src/services/quotationWorkspaceBootstrap.ts`
- `src/services/quotationSync.ts`
- `backend/src/main/java/com/milano/quotation/quote/QuotationSyncController.java`
- `src/views/QuotationSystemView.financeInitialization.test.ts`（新增）
- `src/services/quotationSyncAnomalies.test.ts`
- `backend/src/test/java/com/milano/quotation/quote/QuotationSyncControllerTest.java`（新增）

主目录已有其他任务未提交修改，未纳入本次候选。独立工作区继承线上物流性能优化、起重和欧盟处理费功能，仅移植本次同步修复。

## 验证

- 18 个前端测试文件 / 112 项通过：真实 Vue 组件在模拟员工身份下冷启动、旧缓存进入、五位小数系数、美元/欧元汇率、恢复草稿、等级启停、网络失败重试、空白页后台刷新、七类财务变更核验和物流同步；包含财务/税费/物流缓存现有回归。
- 后端 `QuotationSyncControllerTest`、`PublishedLogisticsControllerTest`：2 项通过。新增测试使用本地 H2 只含版本字段的表，验证七类版本返回、变更可见、无缓存响应和原物流版本保留。
- `vue-tsc -b`、`vite build`、本次修改相关 ESLint 均通过。
- 没有变更生产财务值、正式物流价格、业务记录或培训系统。

- 发布候选全量前端回归：792 项通过，2 项依赖外部原始 Excel 样本的测试按既有条件跳过；全量 ESLint、Compose 隔离校验、类型检查和构建通过。
