# 报价系统强隔离后端、数据库与生产部署实施及测试报告

- 报告日期：2026-08-22
- 实施分支：`codex/quotation-production-backend`
- 基线提交：`698ecbca0027358ec8e8792d557ca54bece16af4`
- 独立工作树：`C:\Users\25490\Desktop\quotation-production-backend`
- 生产状态：未部署
- Git 状态：未暂存、未提交、未推送，等待人工确认

## 1. 实施范围

本次只在独立报价仓库工作树内增加或修改代码。培训仓库、培训数据库、培训容器、培训账号和入口配置均未读写或重启。原报价工作区内已有的未提交 UI 文件也未被纳入该工作树。

已实施：

- 独立 Spring Boot 4 / Java 21 后端、PostgreSQL Flyway 数据模型、Redis Session、MinIO 对象元数据及报价专属 RBAC。
- 采购、物流、财务、报价记录、草稿、模板、账号权限和审计 API。
- Excel 采购导入预览/确认、幂等写入、图片清单及分卷 ZIP 迁移、校验后原子发布。
- 前端业务数据从浏览器存储切换为同源 `/api/v1`；浏览器只保留财务页签顺序这一 UI 偏好。
- 报价专属 Compose、网络、数据卷、日志、版本目录、备份/恢复、回滚、预检、健康检查和域名入口模板。
- CI 隔离扫描、前后端质量门禁、Compose 静态约束。

## 2. 隔离证据

| 隔离对象 | 实现证据 | 当前验证结果 |
|---|---|---|
| 代码 | 后端包名 `com.milano.quotation`；`deploy/scripts/verify-isolation.sh` 扫描培训路径、库名、卷名和桶名引用 | 本地静态扫描通过 |
| Compose | 项目名 `quotation-prod`，5 个 `quotation-*` 服务 | Compose 校验通过 |
| 数据库 | 容器 `quotation-postgres`，库 `quotation_prod`，用户 `quotation_app`，卷 `quotation-postgres-data` | 配置验证通过；容器实测未执行 |
| Redis | 容器 `quotation-redis`，Session 命名空间 `quotation:session`，卷 `quotation-redis-data` | 配置验证通过；容器实测未执行 |
| MinIO | 容器 `quotation-minio`，桶 `quotation-assets`，卷 `quotation-minio-data` | 配置验证通过；容器实测未执行 |
| 网络 | 内部网络 `quotation-internal`，入口网络 `ahmln-edge`；数据服务不加入入口网络 | 配置验证通过；容器实测未执行 |
| 端口 | Compose 无 `ports`，后端只 `expose: 8088` | 静态校验通过；公网封闭未实测 |
| 会话 | Cookie `QUOTATION_SESSION`；培训 Cookie 自动化请求报价 API 返回 401 | H2 接口测试通过；反向生产测试未执行 |
| 备份 | 仅访问 `quotation-prod`、报价库、报价 MinIO 和 `/srv/ahmln-data/quotation-app/backups` | 脚本审查完成；真实备份/恢复未执行 |
| 发布/回滚 | 仅操作 `quotation-*` 镜像、Compose 项目和报价版本目录；发布要求干净 Git 提交 | 脚本审查完成；生产发布/回滚未执行 |
| 域名 | `vip.ahmln.com` 的 `/` 指向报价前端，`/api/` 指向报价后端 | 配置文件完成；TLS 和双域名不串站未实测 |

## 3. 测试明细

状态定义：`通过` 表示本机实际执行成功；`未执行` 不等同于通过；`待数据` 表示功能和工具已完成但缺少权威业务数据。

| 类别 | 测试场景 | 操作步骤 | 预期结果 | 实际结果 | 状态/证据 |
|---|---|---|---|---|---|
| 正常 | 前端静态检查 | 显式加载内置 Node 后运行 `pnpm lint` | ESLint 零错误 | 退出码 0 | 通过 |
| 正常 | 前端单元测试 | 运行 `pnpm test` | 全部用例通过 | 2 个文件、5 个测试全部通过 | 通过 |
| 正常 | 前端生产构建 | 运行 `pnpm build` | TypeScript 和 Vite 构建成功 | 构建成功 | 通过；存在大 chunk 警告 |
| 正常 | 后端质量门禁 | 使用 Temurin 21 和 Maven 3.9.16 运行 `mvn verify` | 单元/接口测试和打包成功 | Maven 退出码 0，JAR 已生成 | 通过 |
| 正常 | 后端应用上下文 | 以 H2 测试配置启动完整 Spring 上下文 | Repository、Security、MVC 可启动 | Spring Boot 测试上下文启动成功 | 通过 |
| 正常 | Compose 结构 | 运行 `node scripts/validate-compose.mjs` | 5 个隔离服务且零发布端口 | 输出 `5 isolated services, zero published ports` | 通过 |
| 正常 | 匿名页面 | 本地前后端启动后访问 `/quotation/overview` | 重定向登录页并保留 redirect | 重定向到 `/login?redirect=/quotation/overview` | 通过；浏览器实测 |
| 异常 | 错误密码 | 登录 API 和本地登录页输入错误密码 | 返回 401，前端展示业务错误，不出现 500 | API 用例为 401；页面展示“账号或密码错误” | 通过 |
| 异常 | 图片伪造类型 | 将非图片字节送入真实类型检测 | 拒绝并返回校验错误 | `AssetStorageServiceTest` 通过 | 通过 |
| 异常 | 弱密码/重复账号 | 创建弱密码或重复账号 | 返回校验错误/冲突 | `UserAccountServiceTest` 通过 | 通过 |
| 异常 | 重复写操作 | 使用同一 `Idempotency-Key` 重复确认/发布/成交 | 返回首个任务结果，不重复落库 | 后端幂等框架和唯一约束已实现 | 未执行；需 PostgreSQL 容器接口测试 |
| 异常 | Excel 错表头/非法行 | 上传缺列、错表头、重复 SKU、非法金额文件 | 返回逐行错误，不写正式数据 | 校验代码已实现 | 未执行；需业务模板夹具 |
| 异常 | ZIP 路径穿越/炸弹 | 上传路径穿越、高压缩比、超上限分卷 | 拒绝并保留失败任务，不发布 | 防护代码已实现 | 未执行；需恶意文件夹具 |
| 边界 | 图片哈希去重 | 对相同和不同字节计算 SHA-256 | 相同字节哈希一致，不同字节不同 | 单元测试通过 | 通过 |
| 边界 | 323 张静态图盘点 | 运行图片盘点脚本，不提供权威 SKU 导出 | 不猜测 SKU，全部进入孤立复核 | 总数 323，映射 0，孤立 323 | 待数据；行为符合设计 |
| 边界 | 10 万商品/20 万图片 | 执行分页、筛选、迁移、恢复、内存压力测试 | 满足生产资源门槛且任务可恢复 | 未在本机执行 | 未执行 |
| 权限 | 匿名访问报价 API | GET `/api/v1/purchase-products` | 401 + requestId | 自动化测试返回 401 | 通过 |
| 权限 | 培训 Cookie 访问报价 API | 携带 `TRAINING_SESSION` 请求报价 API | 不接受外部会话，返回 401 | 自动化测试返回 401 | 通过 |
| 权限 | 首次登录限制 | 临时管理员登录后访问业务 API | 返回 428，要求先修改密码 | 自动化测试返回 428 | 通过 |
| 权限 | 五角色矩阵 | 分别使用超级管理员、财务、物流、采购、员工访问所有 API | 仅允许授权操作；员工只读本人报价 | 权限映射已实现 | 未执行；需 Testcontainers 权限矩阵 |
| 兼容 | 单渠道成交数据 | 读取历史单渠道字段并规范化成交明细 | 页面和接口不丢失旧数据 | 前端兼容转换已实现 | 未执行；缺权威历史导出 |
| 兼容 | 原 Excel 模板 | 上传既定采购/物流模板 | 表头和业务字段保持兼容 | 采购 32 列校验已实现 | 未执行；缺最终模板验收文件 |
| 兼容 | 浏览器业务数据清理 | 搜索 `localStorage`/IndexedDB 业务引用 | 业务数据均以 API 为准 | 仅剩财务页签顺序 UI 偏好 | 通过；静态扫描 |
| 部署 | PostgreSQL Flyway | Testcontainers 启动 PostgreSQL 并执行 V1-V3 | 迁移成功、Schema 校验成功 | 本机无 Docker，测试按条件跳过 | 未执行（1 条跳过） |
| 部署 | 镜像/Compose 启动 | 构建两个应用镜像并启动 5 个容器 | 全部健康，数据服务不暴露端口 | 本机无 Docker | 未执行 |
| 部署 | HTTPS/安全头 | 访问 HTTP/HTTPS、API、图片和 Cookie | HTTP 跳转、TLS 正常、安全头和 Secure Cookie 正常 | 未连接生产 | 未执行 |
| 部署 | 公网 8088 | 从公网请求宿主机 8088 | 拒绝或超时 | 未连接生产 | 未执行 |
| 隔离 | 培训系统回归 | 部署前后验证培训登录、页面、API、文件 | 全程可用，不重启培训资源 | 未连接培训生产 | 未执行 |
| 隔离 | 报价备份/恢复/回滚 | 执行报价备份、恢复测试和版本回滚 | 仅改变报价资源，培训持续可用 | 未连接生产 | 未执行 |

## 4. 自动测试汇总

后端 Surefire 报告：

- `RequestIdFilterTest`：1/1 通过。
- `AuthenticationIntegrationTest`：4/4 通过。
- `UserAccountServiceTest`：3/3 通过。
- `AssetStorageServiceTest`：3/3 通过。
- `FlywayPostgresIntegrationTest`：0/1 执行，因本机 Docker 不可用而跳过。
- 合计：12 条，11 条通过，1 条跳过，0 失败，0 错误。

前端：2 个测试文件、5 条测试全部通过；ESLint、TypeScript、Vite 生产构建均通过。

## 5. 已知限制和生产阻断条件

在以下项目完成前不得标记为“已生产交付”：

1. 用户确认本地变更并允许按职责拆分 Git 提交。
2. 推送远端并核对远端 SHA，生产只从干净提交构建。
3. 取得服务器连接信息并完成只读预检：DNS、TLS、CPU、内存、系统盘、数据盘、Docker 根目录、8088、`ahmln-edge`。
4. 数据盘满足迁移文件总量乘以 3 再加 10GB，且保留至少 20% 空间；部署后内存余量至少 25%。
5. 在 Docker 环境补跑 PostgreSQL/Flyway、五角色权限矩阵、导入夹具、ZIP 安全、真实备份恢复和性能测试。
6. 取得采购/物流最终 Excel 模板、权威浏览器数据白名单和 SKU 图片映射，再执行预迁移。
7. 共享入口变更前后分别执行培训系统只读回归；失败立即停止，不操作培训容器或数据。

## 6. 建议提交拆分（尚未执行）

1. `feat: add isolated quotation backend and database`
2. `feat: migrate quotation frontend to production APIs`
3. `feat: add resumable procurement image migration`
4. `chore: add isolated quotation deployment`

共享 HTTPS 入口配置应单独作为基础设施提交，不与报价或培训业务代码混合。
