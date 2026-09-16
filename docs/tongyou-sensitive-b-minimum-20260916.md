# 通邮美国专线特敏感B：50g最低计费重量

2026-09-16线上只读核对：活动数据集 bd07399a-1e05-4886-b3f6-699ee9c64919，渠道 e8aac69d-e3ba-49a4-84b2-b6279bd99771（ruleId 569），当前V2的8个美国价格段起重及最低计费均为0。

用户明确确认该渠道50g起重并授权修改、提交、部署。旧8.17表的既有提取记录中“美国专线小包”B16为“单票计费起重50克；”。线上V2来自9月14日价表，存储的原表文件已于9月13日删除，且旧解析器未保留表尾起重说明，因此本次依据明确标记为 user-confirmed，不冒充已经重新读取线上原文件。

V41仅修复上述确定数据集、渠道、V2及指纹匹配的8行；最低计费重量写为0.05kg，保留全部原价格、每票费、区间、收寄边界和旧版本。创建新的正式V3，生成逐档计费验收及审计记录。存在草稿、活动导入、重建或基线变化时拒绝执行；没有此数据集的新安装跳过。

首档91元/kg加24元/票。20g、49g、50g都应为28.55元；51g为28.64元；60g为29.46元。补重只作用于整票物流计费，不改商品及关税实际重量。

运行 DocumentedMinimumWeightRepairTest、TongyouMinimumWeightRepairTest、LogisticsSourceParserTest、LogisticsBillingEngineTest 和 FlywayPostgresIntegrationTest。专项测试使用真实V2快照在隔离PostgreSQL中演练V41、新版本验收与回滚。原表解析回归覆盖矩阵表B16的50g备注。

数据回滚使用 deploy/scripts/rollback-tongyou-minimum-v41.sql，只允许当前渠道仍指向本次修复版本时执行；保留所有版本及审计证据。只回退应用镜像不会撤销数据版本。
