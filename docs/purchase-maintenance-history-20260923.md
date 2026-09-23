# 采购产品维护记录

## 本地实现

- 采购列表操作列、编辑资料弹窗底部增加“修改记录”。按时间倒序，每页 10 条，展示北京时间、登录用户姓名和账号、操作类型、SKU、字段中文名称及修改前后值。
- 记录手工新增/粘贴新增、编辑资料、独立图片上传、启用/停用、模板转正式。Excel 导入及后台批量图片关联仍使用现有导入任务审计，不在本入口伪造逐字段维护历史。
- 使用已有 `audit_log` 持久化，`resource_type=purchase-product-history`，`resource_id` 为产品 UUID。字段差异由服务器根据保存前后的真实数据计算；姓名来自会话，账号由审计服务获取，客户端不能指定记录操作者。
- 日志与商品变更共用事务，失败或版本冲突不会留下成功记录；无业务字段变化不生成记录。衍生字段、版本号等不重复列出。
- 产品改 SKU 改用原子维护接口，保持产品 UUID 和图片关联，保留已有业务引用保护；目标重复或保存失败不会先删除原商品。删除后用同 SKU 新建的商品不会混入旧商品历史，旧审计记录仍保留。
- 图片字段显式清空优先于旧兼容字段，避免删除图片后被旧 `image` 字段带回。
- 新接口 `GET /api/v1/purchase-products/{sku}/history` 和 `POST /api/v1/purchase-products/{sku}/maintenance` 均要求 `PERM_purchase`。已有保存接口也进入服务层记录逻辑。
- V49 为审计查询增加 `(resource_type, resource_id, created_at DESC, id DESC)` 索引，不回填不存在的历史字段信息。界面说明功能启用前的修改明细无法追溯。

## 验证

- 前端：PurchaseHistoryDialog、PurchaseDataWorkspace、purchaseStore、purchaseProductActions，4 个文件共 23 项测试通过。
- 后端：PurchaseHistoryIntegrationTest 6 项、PurchaseProductServiceTest 19 项、PurchaseProductControllerTest 4 项、AuditServiceTransactionIntegrationTest 3 项，共 32 项通过。
- 集成测试包含真实数据库事务（H2 测试配置）、HTTP 权限、登录用户姓名、前后值、分页、无变化保存、旧版本冲突、回滚、图片移除、停用、SKU 连续性和同 SKU 重建隔离。H2 不支持现有 PostgreSQL 引用检查语法，因此该集成类替换了引用检查依赖，并覆盖允许/阻断两种返回；不等同于 PostgreSQL 完整验收。
- 修改范围 ESLint、`pnpm build`（含 TypeScript 检查）通过；差异检查无空白错误。

## 发布范围与并行任务协调

用户已授权提交和上线。候选使用独立分支 `codex/purchase-maintenance-history-20260923`，基于已发布的 `10a8eca`，并保留先行发布的报价布局提交 `cbdde7b`。共享主目录及其他任务未提交代码保持原状；本次不包含尚未上线的财务审核领取或归档/回收站功能。

已协调迁移编号：采购本次 V49，财务审核暂用 V50，归档/回收站暂用 V51；未来发布仍需根据线上版本重新核对。采购需前后端一起更新，先在 GitHub 质量门禁验证完整 PostgreSQL 迁移链，再通过独立服务器工作区发布。同时持有 `maintenance/release.lock` 和 `/tmp/quotation-production-release.lock`，锁内核对上一版本，避免并行发布覆盖。生产备份、最终 SHA/版本和验收结果随发布证据记录；上面的本地测试结果不代表生产验收。
