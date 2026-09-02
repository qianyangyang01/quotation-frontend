# Milano 报价经营系统

独立的 Vue 3 + Spring Boot 报价系统。业务数据由报价专属 PostgreSQL、Redis 与 MinIO 保存，浏览器只保存非业务 UI 偏好。生产域名为 `vip.ahmln.com`，后端仅在容器网络监听 `8088`。

## 本地构建

```bash
pnpm install --frozen-lockfile
pnpm build
cd backend && mvn verify
```

## 生产资源边界

- Compose 项目：`quotation-prod`
- 数据库/账号：`quotation_prod` / `quotation_app`
- 对象桶：`quotation-assets`
- 网络：`quotation-internal`、`ahmln-edge`
- 数据卷：`quotation-postgres-data`、`quotation-redis-data`、`quotation-minio-data`
- 发布根目录：`/srv/ahmln-data/quotation-app`

报价服务不共享其他业务系统的代码、账号、会话、数据库、缓存、对象桶、数据卷、日志或备份。唯一共享项是宿主机最外层 HTTPS 入口，且只按域名反向代理。

生产部署必须先阅读 [部署说明](deploy/README.md)，执行预检、隔离检查和备份；不得直接使用带未提交变更的工作区构建。
