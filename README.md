# 米莱诺报价系统

这是从培训系统中独立出来的报价前端，包含业务报价、采购资料、供应商、物流规则、财务设置和报价记录页面。

## 本地启动

```powershell
cd C:\Users\25490\Documents\kaohe\quotation-frontend
pnpm install
pnpm dev
```

默认地址：`http://localhost:5174/quotation`

## 生产构建

```powershell
pnpm build
```

报价草稿继续使用原来的 `milano-quotation-draft` 浏览器存储键，因此同一浏览器中的旧草稿不会因拆分而丢失。
