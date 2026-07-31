---
name: "專案健康檢查"
description: "完成變更後檢查 WorldManagement 的契約偏移、測試缺口、Paper 執行緒風險與操作回歸。"
argument-hint: "變更功能、檔案或可選的檢查重點"
agent: "ask"
---

以繁體中文對 WorldManagement 進行唯讀健康檢查。不得編輯檔案、執行建置、啟動伺服器、修改儲存體或遷移 metadata。

`AGENTS.md` 為強制規範。先依提供的重點與可用的目前 Git diff/status，再檢查直接相關的 source、聚焦測試、resources 與文件。使用 `docs/implementation-status.md` 區分已知限制與回歸。

僅檢查下列具體風險：

- Paper 執行緒親和性、阻塞 I/O、scheduler callback、future 等待與 shutdown 行為。
- metadata/cache 一致性、repository abstraction、單一 provider 儲存、audit 結果完整性與路徑驗證。
- bypass、access-control、owner、rank、Warp 與指令權限的 authorization 順序。
- 未記錄、過期或未一致本地化的可觀察指令/設定行為。
- 已變更行為或高風險失敗路徑缺少聚焦測試。

不得回報風格偏好或推測性重構。沒有具體發現時須明確說明，且只列出剩餘測試缺口。

依此格式輸出：

1. **發現**：按嚴重度排序；每項包含檔案路徑、風險與最小修正方式。
2. **驗證與覆蓋**：已知檢查、缺少的聚焦測試，以及 runtime smoke test 是否有價值。
3. **文件契約**：僅列出 source 行為與 `docs/` 或 resource 預設值的不一致。
4. **建議順序**：最多三個依影響排序的下一步。

檢查重點：${input:focus}