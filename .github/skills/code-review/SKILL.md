---
name: code-review
description: "用於審查 WorldManagement branch、PR 或工作中變更；分別檢查專案標準與需求契約，並在 Paper 執行緒、I/O 或效能風險存在時納入專門審查。"
argument-hint: "固定比較點、spec/需求路徑或審查範圍"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 兩軸程式碼審查

對固定比較點與 `HEAD` 的 diff 分開執行 **標準** 與 **規格** 審查。審查不修改檔案、不執行建置、不以風格偏好取代具體風險判斷。

## 前置條件

1. 使用者必須提供固定比較點，例如 commit、branch、tag、`main` 或 `HEAD~5`；沒有時要求提供。
2. 在可用 Git metadata 下，先確認 `git rev-parse <fixed-point>` 成功、以 `git diff <fixed-point>...HEAD` 取得 diff，並以 `git log <fixed-point>..HEAD --oneline` 列出 commits。若 repository metadata 不可用，要求使用者指定檔案/目錄範圍，並明確說明無法形成 merge-base diff。
3. 規格來源依序採用：使用者本次需求、使用者指定文件、`plan/`、`docs/`、commit/PR 描述；找不到時保留標準軸並標示「無 spec 可比對」。不得要求設定 issue tracker 才能開始審查。

## 標準軸

以 `AGENTS.md` 為強制規範，並檢查與 diff 直接相關的 `README.md`、`docs/architecture.md`、`docs/command-architecture.md`、`docs/commands.md`、`docs/configuration.md`、resource 設定與聚焦測試。優先尋找具體的行為、資料完整性、相容性、文件、測試與安全風險。

特別檢查：

- 遊戲執行緒上的 blocking I/O、JDBC/YAML/filesystem/audit/backup、`Future#get` 或 `join`。
- 非同步後 world/location/entity 的 thread affinity，`PluginIoExecutor` 與 `WorldThreadDispatcher` 邊界。
- 單一 metadata provider、repository abstraction、cache snapshot、world name/path validation。
- access-control、owner、rank、Warp、permission visibility、alias 與 snapshot-only completion。
- Java 25/Paper API 使用、已棄用 API、聚焦測試與可觀察契約文件。

若 diff 涉及上述任何項目，另行使用 `Paper 效能審查員` 的唯讀意見，並在標準軸中獨立列出其發現。可補充標示命名、重複程式碼、primitive domain value、message chain 或多餘抽象等設計疑慮，但必須標記為 judgment call，不能當成硬性違規。

## 規格軸

逐條比較 spec 與 diff，回報：缺漏或部分實作、未要求的 scope creep、看似已實作但與 spec 不符的行為。每項都要引用 spec 來源與對應 diff hunk；沒有 spec 時略過此軸。

## 輸出

輸出順序固定為：

1. **標準**：按嚴重度排序的具體發現，包含檔案/行號、違反的規則、風險與最小修正；再列出 judgment calls 與測試缺口。
2. **規格**：以規格條目分組的缺漏、scope creep 或錯誤實作。
3. **摘要**：各軸發現數量與各自最嚴重項目。不得把兩軸合併排序。