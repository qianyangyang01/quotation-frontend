# 采购粘贴票点必填与一次性补齐

采购粘贴新增必须明确填写票点，允许 0%，前后端均校验。全部新记录校验通过后才写入；已有 SKU 和批内重复 SKU 仍按原规则跳过。其他新增、Excel 导入和旧数据规则不变。

财务客户操作费表头明确为“1件订单、2件订单、3件订单、3件以上订单”，金额标为“操作费（美元）”。只调整说明，数量选档与每单加一次的计算不变。

## 一次性维护

`deploy/scripts/repair-pasted-tax-point.py` 仅处理 `dataSource=standard`、`sourceSheet=采购粘贴新增` 且票点缺失、null 或空字符串的记录。8% 存为数值 0.08；显式 0% 不处理。

在报价服务器运行（批次目录必须是新目录）：

```sh
python3 deploy/scripts/repair-pasted-tax-point.py prepare --batch-dir /srv/ahmln-data/quotation-app/backups/paste-taxpoint-20260918-01
# 核对 manifest.json、changes.csv 和 before.jsonl 后，将 N 替换为实际确认数量。
python3 deploy/scripts/repair-pasted-tax-point.py apply --batch-dir /srv/ahmln-data/quotation-app/backups/paste-taxpoint-20260918-01 --expected-changes N
```

备份包含完整目标行和哈希。事务核对目标行未变化后，仅更新票点、版本、修改时间；核对非目标采购、其他目标字段和全部历史报价不变，再写入批次审计。版本更新沿用现有草稿保存冲突检查。

需要撤销本批次时，使用同一脚本 `rollback` 模式及同一目录、数量。回退必须匹配修正后完整行，任何后续改动都会使整批回退终止，避免覆盖新修改。回退保留递增版本及审计记录。失败或连接异常后先检查批次审计，不盲目重试。

本地隔离 PostgreSQL 测试：`python deploy/scripts/test_pasted_tax_point.py`，覆盖来源边界、显式零、完整保留其他字段、并发冲突、回退、备份篡改和数量不符。
