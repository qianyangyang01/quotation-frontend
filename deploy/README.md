# 报价系统生产部署

## 发布后仍打开旧页面的兼容

前端 `/assets/` 使用独立持久卷 `QUOTATION_FRONTEND_ASSETS_VOLUME`（默认 `quotation-frontend-assets`）。新镜像启动时只追加构建文件，同名但内容不同会阻止启动；不覆盖或自动删除旧文件。HTML 仍来自当前版本镜像并禁止缓存，因此新页面使用新版本，旧页面的延迟加载详情仍可读取原文件。

正式发布脚本会在切换前运行 `retain-frontend-assets.sh <候选前端镜像>`，将旧容器的静态文件和候选镜像文件写入持久卷并校验。首次升级也必须执行此步骤，不能先删除旧容器。前端单独发布或使用本地预构建镜像时，同样必须包含 Dockerfile 中的保留脚本、更新后的 Compose 卷配置，并在 `up -d --no-deps quotation-frontend` 前执行上述脚本。不要仅复制 `dist` 和旧 Nginx 配置来构建发布镜像。

回滚脚本会继续挂载同一静态文件卷，支持回滚到尚未内置保留脚本的旧镜像；新旧页面的文件均保留。独立 UAT 环境必须设置不同的 `QUOTATION_FRONTEND_ASSETS_VOLUME`，不得共用生产卷。此卷只保存公开构建文件，不包含报价或数据库数据。检查 `docker system df -v` 监控空间；不要随发布清理此卷，任何手动淘汰都会使仍打开的旧页面需要刷新。

页面资源加载失败时提供手动“刷新页面”入口，不自动刷新包含未保存内容的页面。验证必须覆盖：打开旧页面后发布新版、旧详情 JS/CSS 仍返回 200 且字节一致、新 HTML 指向新版、回滚后两版文件仍能读取、真正不存在的文件仍返回 404。

应用发布对 PostgreSQL、Redis、MinIO 使用 `pull --policy missing`：复用本机已经存在的配置版本，只有镜像缺失时才拉取，拉取失败仍会阻止发布。数据服务升级应单独更改并验证镜像版本，不随应用发布刷新相同标签。这样已有生产实例不会因为镜像仓库入口暂时不可用而被迫更换存储版本。集成测试使用 MinIO 官方 Quay 仓库的同一固定版本。

1. 在独立、干净的报价发布目录复制 `.env.example` 为 `.env`，生成随机密钥；`.env` 权限设为 `0600`。
2. 确认数据盘容量不低于迁移文件总量的三倍再加 10GB，且部署后至少保留 20% 磁盘与 25% 内存余量。
3. 创建一次外层入口网络：`docker network create ahmln-edge`。该网络只用于域名反代，不接入数据库、Redis 或 MinIO。
4. 先记录其他业务域名的页面/API/文件服务基线，只读验证通过后再执行 `bash scripts/preflight.sh`。
5. 从已经推送且核对 SHA 的干净报价提交检出发布目录，执行 `bash scripts/release-version.sh quotation-YYYY.MM.DD-NN`。脚本在该提交上构建前后端同版本本地镜像；已有报价栈会先备份，首次安装会在清单中明确记录无前序备份。
6. 外层 Nginx 仅新增 `nginx/vip.ahmln.com.conf`；执行 `nginx -t` 后无中断 reload。不得修改其他域名的 upstream。
7. 脚本先执行 5 个报价容器健康检查；入口启用后再执行 `bash scripts/healthcheck.sh`、角色权限矩阵、Cookie 交叉拒绝与其他业务基线回归。
8. 首次发布完成后执行 `bash /srv/ahmln-data/quotation-app/current/deploy/scripts/install-backup-cron.sh`；该脚本只维护 `ahmln-quotation-managed` 标记块，不修改培训备份任务。每周完整报价备份校验成功后只保留最近 2 份。

供应商主数据下线版本发布时，`release-version.sh` 会在切换容器前先执行候选版本的 `export-supplier-master-data.sh`，再调用当前生产版本的完整备份。专项导出会生成供应商、商品关联 CSV、行数清单和 SHA-256；任何缺表、行数或校验不一致都会终止。供应商表已完整移除后的后续版本会记录 `not-applicable-already-removed` 并安全跳过专项导出。

独立供应商记录功能上线后的每次升级，`release-version.sh` 还会在数据库迁移前执行 `export-supplier-records.sh`。该导出包含 `supplier_record` 全字段 CSV、UUID 清单、行数清单和 SHA-256，目录及文件权限分别限制为 `0700` 和 `0600`；缺表、行数或校验不一致都会终止发布。

历史浏览器数据须在旧页面先执行 `../scripts/browser-authority-export.js`。导出文件默认排除本地账号、会话、草稿和测试键，仍须人工批准白名单。用 `node ../scripts/inventory-static-images.mjs ../public/purchase-images authority.json > image-inventory.json` 盘点 323 张采购静态图；脚本只接受浏览器记录中的精确文件名映射，其余进入 `orphan-review`，不猜测 SKU。

数据库、Redis、MinIO没有 `ports` 映射；后端只有 Compose `expose: 8088`。备份与恢复脚本只引用 `quotation-prod`、`quotation_prod` 和报价专属目录。对象恢复须先在演练环境通过对象清单校验，禁止用未验证的覆盖命令直接写生产桶。
