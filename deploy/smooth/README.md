# 报价平滑发布

普通前端和兼容的网页接口更新使用独立候选实例，确认就绪后通过报价专属 Nginx 网关 reload 切换。网关不重试写请求，不强制刷新页面。旧进程继续完成保存、上传和下载，旧实例及其镜像保留供回退。

已有 `quotation-prod-quotation-backend-1` 保留为独立后台任务服务和首次回退目标。数据库、Redis、MinIO、解析器、培训应用均不重建。新网页实例关闭调度、启动恢复、物流任务直接派发、Flyway 和初始化写入；物流上传写入原有持久队列，由原后台任务服务处理。石墨同步接口固定路由到该任务服务。价格历史的按需投影保留原有幂等行为，不重算历史报价。

## 发布边界

- `compatibility.py` 检查后台任务入口的项目类依赖闭包、全部依赖库及资源。迁移文件只容许 CRLF/LF 格式差异，任何内容变化阻止普通发布。候选实例本身禁用 Flyway。
- 首次接入的少量网页隔离改动使用逐类、逐版本 SHA-256 双向匹配，不采用类名通配豁免。网页隔离实现也固定哈希，修改后必须重新演练。
- 后台任务及其依赖逻辑变化必须使用单独验证的任务服务升级流程或约定维护窗口。不得让旧任务服务默默执行不兼容的新任务，不得把整机重启、数据库变更宣称为无感发布。
- 生产新增网页实例限 1 GiB，预留至少 25% 且不低于 1 GiB 可用内存。网页实例最多同时运行两个。隔离环境的低负载功能演练使用显式限定的本地例外，不适用于生产。

## 首次安装

先运行 Java 测试、Python 失败路径测试和本地真实 HTTP 演练。`prepare_rehearsal.py` 仅创建 `quotation-smooth-uat-*` 资源，数据库名为 `quotation_smooth_test`，不读取生产业务数据。Docker Desktop 内网默认不向宿主机开放端口时，使用演练工具容器访问内部入口。

生产配置中的 `root` 为 `/srv/ahmln-data/quotation-app/smooth-release`；`edge_file` 必须由实际容器挂载核实。配置、运行记录和原入口备份均保留在此目录。不要把 `.env` 或会话写入测试报告。

```sh
python3 release.py --config config.json bootstrap
python3 release.py --config config.json install
python3 release.py --config config.json prepare --release quotation-YYYY.MM.DD-NN --image quotation-backend:quotation-YYYY.MM.DD-NN
python3 release.py --config config.json switch --release quotation-YYYY.MM.DD-NN
python3 release.py --config config.json status
```

`bootstrap` 只启动网关并指向原服务，不改变公开路由。`install` 只替换报价虚拟主机中两处已核对的 upstream，先 `nginx -t`，再平滑 reload；应用容器不停止。首次安装后每次切换仅 reload 报价网关，不再修改共享培训入口。正式接入同时记录其他系统只读访问基线、生产容器身份和历史报价/审计基线。

## 后续发布及回退

1. 从干净提交构建并标记镜像，在隔离环境验收接口兼容、业务保存、上传、后台队列及历史快照。先确认没有并行发布。
2. 执行 `prepare`；更新前端时附加 `--frontend-image IMAGE`，使用已有静态资源保留卷，碰撞检查失败立即终止。
3. 候选就绪后执行 `switch`。发布锁避免并发切换；切换意图先持久化，配置检测或公开接口验收失败恢复旧路由，不终止在途请求。切换后继续只读观察，核对价格快照和正常编辑审计。
4. 回退使用 `rollback`，只改路由，不回滚数据库、不覆盖报价。运行记录及精确容器 ID 位于 `state.json` 和 `events.jsonl`。这两个文件是网页服务当前版本的依据；原 `current` 发布包继续描述后台任务与数据服务。
5. 下次准备前，若已有两个网页实例，可对非当前实例执行 `retire --release VERSION`。只在当前服务健康、网关旧工作进程已排空时停止已标记的网页实例；不删除容器、镜像或静态资源。保留的回退实例若已停止，`rollback` 会先启动并验收到就绪，再切换；原后台任务服务绝不由此自动停止或重启。
6. 进程或机器异常中断切换后先执行 `recover`，按持久化意图恢复原路由。发现人工配置或容器身份变化时停止处理，不覆盖他人修改。新发布前必须处理未完成意图。

旧 `release-version.sh` 和 `rollback.sh` 在发现平滑发布状态后拒绝重建整个 Compose 栈。勿绕过保护直接执行生产 `docker compose up --force-recreate`。数据库和导入任务升级需按发布边界另行处理。

Nginx reload 的在途连接行为依据：[官方进程控制说明](https://nginx.org/en/docs/control.html)。实际可用性以本项目隔离演练和生产只读验收为准。
