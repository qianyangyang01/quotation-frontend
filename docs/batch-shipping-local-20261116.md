# 批量仅代发货报价：本地验收

状态：仅本地实现，未部署、未推送，未访问生产 API / 数据库。
工作区：C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/batch-shipping-local-20261116
分支：codex/batch-shipping-local-20261116
基线：dcc538a7fc0d9a3200868f9fb1cfa5f06c49935e。
主项目未提交修改未动。本工作区 node_modules 为主项目依赖目录的 junction，未安装或修改依赖。

## 实现

- /quotation/batch-shipping；单条仅代发货工作台提供“批量录入”入口。
- 1–50 条重量（g）及备注，支持逐行添加、复制、删除、勾选和 Excel 两列粘贴；相同重量/备注保留独立身份。
- 共用批量单品的条件、渠道、模板、价格计算和报价单编辑器。每条按实重计算，商品成本为零，无包材附加重量，不查询采购 SKU。
- 每条克重以分隔线分组；数量列、客户价格、平均报价、图片、署名/联系方式、预览/复制沿用报价单功能。
- 通过原 /quotations/batch 事务接口保存，每个录入行一条普通 shipping-only 记录；拥有自己的编号、报价快照及原有审核/跟进流程，quotationBatch 关联本批。
- 同一提交失败重试沿用幂等键；有失败/未完成项时不得直接保存整批，可重试或明确取消选入。
- 新增 shippingBatchSheet 仅保存新批量代发记录的已验证报价单版式；记录展示使用历史快照，未增加历史重算或状态更新逻辑，无数据库迁移。

## 本地预览

http://127.0.0.1:5187/quotation/batch-shipping

启动：node node_modules/vite/bin/vite.js --config tests/manual/batch-shipping.vite.config.ts
浏览器验收：node tests/manual/batch-shipping-browser.cjs（本机 Edge + Codex runtime Playwright）

这是实际 Vue 功能页面 + 本地演示 API，采用明确标注的演示费率，保存仅进入该浏览器 localStorage。Vite 没有后端代理，/api/ 穿透请求返回 503；fixture 拦截外部 fetch。可从 Excel 粘贴重量、备注，填写客户，加载渠道并应用“美国＋英国 · 3条演示渠道”模板。
页面刷新可保留演示报价记录；未保存的录入清单按离开提示处理。普通采购报价在此演示环境中不提供采购资料。

## 已完成验收

- vue-tsc 类型检查、相关文件 ESLint、Vite build 通过。
- 8 个相关前端测试文件，160 项通过；包含原单条代发、批量单品/女装及新增批量代发。
- 后端 23 项单元/契约测试通过：其中将真实浏览器生成的 20 条请求（新增5件列、逐条平均价）交给真实 QuotationSubmissionValidator / CustomerQuotePrices 校验。源版本/物流服务依赖单独 mock，未用生产数据。
- PostgreSQL Testcontainers 集成测试 21 项通过、0跳过。新代发用例验证20条独立落库、幂等重复提交、中途写入失败完整回滚、重试20条、旧记录及已审核状态完整不变。该套集成测试的价格来源 guard 使用 mock，数据库、事务、权限与幂等为实际实现。
- Edge 实际页面：20条/3渠道，包含两条相同200g和备注；新增5件价格列；每条生成美国两渠道平均价；图片上传保存；生成20条记录；列表/详情/重新打开报价单并校验标题与图片。无外部 API 请求。
- 真实浏览器验收发现并修复：重复克重新增数量列沿用旧列；快速渠道模式变更重量时手动输入监听误清空已验证规则缓存。现每条刷新数量列，批量恢复统一管理缓存并逐条重新计价/校验。

截图和运行日志：outputs/batch-shipping-local/（01-input、02-channels、03-sheet、04-records、05-detail、06-preview、07-record-sheet）。
后续若上线，仍需另行用户授权及对应环境验收；本次未发布。

后续全面回归、发现问题与修复结果见 [2026-11-16 全面回归记录](batch-shipping-regression-20261116.md)。该记录补充并更新上述早期定向验收结果。
