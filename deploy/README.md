# 报价系统生产部署

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
