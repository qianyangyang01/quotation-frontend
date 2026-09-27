# 报价单商品图片粘贴

- 基线：线上 `quotation-2026.09.27-01`，SHA `9a7bf9eac47f4ca8adbae3084078c4c6a387f663`；只读核对线上 manifest.txt。
- 分支：`codex/quote-image-paste-20260927`。
- 变更：客户报价单的临时商品图区通过“添加图片 / 管理图片”打开收集弹窗。弹窗内支持“粘贴图片”、Ctrl+V / ⌘V 和多选文件，连续追加到待确认缩略图列表；每张可移除，最后一次确认更新报价单。取消保留原图及已有预览。文件选择与粘贴统一最多 6 张，每张不超过 10MB，保留 JPG / PNG / WebP 和解码尺寸限制。
- 兼容：优先读取剪贴板图片文件，网页富文本可提取内嵌图片或允许跨域读取的 HTTPS 图片。只读链接使用无凭据请求、超时和大小限制，生成浏览器本地图片；HTML 不插入页面。无法读取时明确提示打开原图“复制图片”或截图粘贴。
- 生命周期：弹窗新图独立持有，取消、移除、卸载释放新图；旧图仅在确认删除时释放。读取中允许取消，迟到结果释放且不写回。切换商品、账号/记录、离开页面时关闭弹窗并清理。普通输入框粘贴不受影响。图片维持页面临时展示，不写入采购、报价快照、数据库或服务器。仅用户点击或粘贴时读取剪贴板，不增加后台自动收集。

## 本地验证

- 6 个测试文件、118 项通过：quotePhotoClipboard、quoteLocalPhotos、CustomerQuoteSheet.photos、CustomerQuoteSheet、customerQuoteSheetRenderer、QuotationSystemView.photoReset。覆盖弹窗连续收图、确认前不改变报价、取消保持原图、资源释放、迟到读取、键盘焦点与 Escape。
- `vue-tsc -b`、Vite 生产构建、变更 TS/Vue 文件的 ESLint 和 `git diff --check` 通过。
- 浏览器本地组件页面实测：真实 PNG 剪贴板、粘贴按钮、Ctrl+V、连续追加 6 张、第 7 张拦截、移除单张后继续添加、确认后 6 张进入报价单、管理弹窗移除一张后取消仍保留原 6 张；6 张报价预览为 2 列 × 3 行，全部可见；浏览器无控制台错误。
- 临时验证页及截图在被忽略的 `tmp/quote-paste.html`、`tmp/quote-photo-picker-six.png`、`tmp/quote-photo-picker-preview.png`。
- 尚未用实际石墨文档验证其复制格式；源码未发布到生产，未执行生产业务写入。
