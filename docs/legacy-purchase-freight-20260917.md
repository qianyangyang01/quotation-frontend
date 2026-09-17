# 旧文件导入采购运费修正

生产批次 `legacy-freight-20260917-01` 已于 2026-09-17 15:05:57（北京时间）提交事务。

## 范围和结果

只处理能同时匹配原导入商品 ID、文件 SHA-256 和未回滚的 `legacy-2026` 导入任务的 `legacy_2026` 记录。14 个任务均为 2026-09-09 的旧文件导入，包含 20,539 条现存商品；不按 SKU 日期或单独的旧数据标签推断来源。

|处理|数量|
|---|---:|
|修改单件采购运费|18,315|
|本来符合规则|455|
|保留包邮|272|
|保留缺失或无效重量|1,475|
|保留超过 10,000g 的异常重量|22|
|另行排除没有旧导入批次关联的旧标签记录|160|
|新数据全部未修改|2,456|
|其中粘贴新增记录未修改|1,016|

规则为人民币/件 `max(1, ceil(weightG / 50) * 0.5)`。小数克向上计入下一档；只处理 `0 < weightG <= 10000` 且非包邮。523g 的 BK2600840 已从 5 改为 5.5，569g 的 HC2600622-1 已从 4 改为 6。异常 HC2600302 的重量 480490g、运费 2 及版本均未改变。

数据库事务逐项核对所有采购记录的非目标字段、版本和历史报价；本地独立比较全部修改前后快照再次通过，4,840 条范围外/无需修改记录完全相同。执行后全量查询不符合公式的目标记录为 0。

## 备份、执行和回退

服务器批次目录：`/srv/ahmln-data/quotation-app/backups/legacy-freight-20260917-01`。
本地镜像：`C:\Users\25490\Documents\ProjectWorkspaces\quotation\release-packages\legacy-freight-20260917-01`。

- `before.jsonl` / `after.jsonl`：全量采购快照；`provenance.jsonl`：商品与旧文件导入任务的关联证据。
- `changes.html`：18,315 条修改前后对照；`heavy.html` / `invalid.html`：两类异常清单。
- `manifest.json` / `apply-receipt.json`：校验值与事务结果；本地 `independent-verification.json` 为独立复核结果。
- 修改前快照 SHA-256：`3c2937772ab9c3005a06f0c9129adac263e6e769565c4d29adace75b5b0718bf`。
- 修改后快照 SHA-256：`84915756f05a41ee30ef933f9c33b544fd2e156762d385767ade7d4eee06bb61`。
- 完整归档 SHA-256：`9980e426dc34d77e938834b2fc4b5396e3e1dc57119d7c4cce6dfc2d253d9935`。

维护脚本为 `deploy/scripts/repair-legacy-purchase-freight.py`，服务器副本位于 `/srv/ahmln-data/quotation-app/maintenance/legacy-freight-20260917/`。它要求先 `prepare` 到全新目录，再核对报告并 `apply --expected-changes N`；已记录成功审计的批次禁止重放。报告和快照属于业务数据，不提交 Git。

仅在确需撤回本批次时运行以下命令，不能当作普通发布回退步骤执行：

```sh
python3 /srv/ahmln-data/quotation-app/maintenance/legacy-freight-20260917/repair-legacy-purchase-freight.py rollback --batch-dir /srv/ahmln-data/quotation-app/backups/legacy-freight-20260917-01 --expected-changes 18315
```

回退要求目标商品仍与本批次修改后快照完全一致，遇到后续编辑整批拒绝；恢复原运费（包括原先缺失字段），版本继续递增，不回拨。培训系统与新导入规则均未改动。

## 员工同步及验证

- 生产权限只读核对：13 个启用的 employee 账号有 `quote` 权限，11 个启用的 purchase 账号有 `purchase` 权限。
- 采购接口允许 `purchase`、`quote`、`allRecords` 读取同一份服务器数据，编辑仍要求 `purchase`。
- 报价页面正常约每 5 秒检查采购版本，回到页面时也会检查；发现变化显示“采购资料已更新”，由“更新报价”重新读取并计算。旧版本不能保存，不静默改动员工正在编辑的报价。
- 采购页面刷新/重新查询后读取最新运费；新报价读取最新数据。既有历史报价快照未重算。
- 独立 PostgreSQL 修正/回退测试 10 项通过；前端采购数据、粘贴与同步回归通过；采购服务测试 19 项及员工权限接口测试 6 项通过。
- 权限测试使用本地 Spring Security 的员工身份及真实采购读写服务，验证新值/版本读取、旧草稿拒绝、员工写入禁止和无权限读取禁止。H2 测试环境原有物流清理定时任务提示缺表，不影响这 6 项断言；未改动该无关模块。
- 生产 6 个报价容器健康，后端 readiness 为 UP，CSRF 接口 HTTP 200。登录后的员工浏览器验收尚未完成：当前验收页停在登录页。

## 下拉框文案

另按用户要求，仅将报价条件下拉框选项文字改为“起订量 / 阶梯价2 / 阶梯价3”。原值 `10 / 100 / 100+`、事件、价格映射和计算函数均不变，字段标题保持原样。
