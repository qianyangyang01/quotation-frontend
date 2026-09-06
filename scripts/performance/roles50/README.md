# 50角色隔离复测

此目录所有HTTP负载脚本仅允许127.0.0.1。不得修改为生产地址。

1. 使用独立Compose项目、数据库quotation_perf、回环端口建立隔离环境。先执行上级目录seed.sql，再执行seed-roles.sql，配置20业务/28采购/2物流；直写种子后重启隔离后端与Redis，避免旧登录角色缓存。不要在负载运行时重新灌种子。
2. 本轮正式证据使用已保存的本地规模快照，起始12,293采购商品、175物流渠道、4,911发布价格行。基础seed.sql仅有简化物流夹具；不能用它的结果冒充相同数据规模复测。快照与.env不提交Git，测试前恢复相同快照并ANALYZE。
3. 设置PERF_BASE_URL=http://127.0.0.1:18098、PERF_USERS=50、PERF_ROLE_MIX=true、PERF_WARMUP_SECONDS=30、PERF_DURATION_SECONDS=1800、PERF_OUTPUT=artifacts/performance/accepted-soak-50.json。
4. `node scripts/performance/roles50/monitor.mjs` 运行持续负载并每10秒采集容器与连接数据。monitor中的容器前缀必须对应独立quotation-interaction-perf项目。测试期间不要构建、跑其他压测或调整资源。
5. `accounts-abnormal.mjs` 单独进行权限、幂等、乐观锁等异常测试，输出路径同样使用PERF_OUTPUT。
6. `purchase-import.mjs`、`logistics-import.mjs`各占一个额外测试会话，用IMPORT_OUTPUT、LOGISTICS_IMPORT_OUTPUT设置报告位置。采购1000行；物流1行标准表，在新建停用物流商/渠道上审核，不启用渠道。这不是50人同时导入测试。
7. `search-probe.mjs`以一个额外只读会话，对5个关键词各取30样本，PROBE_OUTPUT指定文件；数字应独立于50主会话统计。测试密码使用独立夹具或PERF_PASSWORD，禁止使用生产人员密码。

主负载包括真实隔离写入；即使PERF_READ_ONLY=true，预检也会创建本地报价和必要的夹具计费验收，不可用于生产只读诊断。报价规则变更会故意触发旧revision拒绝，不能在主负载中启用其他渠道并仍要求所有报价成功。

最终证据与限制见docs/performance/quotation-roles50-results-2026-09-06.md。
