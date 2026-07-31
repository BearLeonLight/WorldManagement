---
name: tdd
description: "用於 WorldManagement 的測試先行功能開發、bug 修復或聚焦 integration test；以 Gradle/JUnit 的 red-green 小切片驗證領域、持久化與指令契約。"
argument-hint: "功能或 bug、預期行為、測試邊界與相關檔案"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 測試驅動開發

使用 red-green 小切片建立可維護的行為測試。一般功能工作仍以 `.github/prompts/feature-development.prompt.md` 與 `.github/prompts/feature-modification.prompt.md` 為主；此 skill 只規範測試先行的實作迴圈。

## 權威與測試邊界

- `AGENTS.md`、`docs/`、resource 設定、公開服務介面與使用者確認的行為契約是權威。
- 每一個測試前，先寫明它驗證的公開 seam 與預期行為。只透過公開 service、repository abstraction、`CommandRoute` 或已定義 parser API 觀察結果。
- 不測 private method、暫態實作細節或 collaborator 呼叫順序；mock 僅用於隔離不可控外部邊界。
- 不得為測試引入阻塞等待或忽略 Paper thread affinity。涉及 world、location 或 entity 的非同步完成情境，透過 `WorldThreadDispatcher` 所定義的邊界測試。

## Red-Green 迴圈

1. **確認一個切片。** 明確記錄單一行為、seam、已知輸入、預期輸出與是否要更新文件。需求改變 world lifecycle、權限、儲存或指令語意時，先由開發者確認範圍。
2. **Red。** 在既有 `src/test/` 結構新增一個聚焦 JUnit 測試。預期值必須來自已確認的規格、已知 literal 或獨立 worked example；先執行最窄的 Gradle test，確認它因目標行為而失敗。
3. **Green。** 只寫讓該測試通過所需的最小 production code。不得順手加入未測試的 abstraction、參數、hook 或下一個功能切片。
4. **再驗證。** 重新執行同一個聚焦測試，然後選擇相鄰測試或最窄的 module verification。結果不明時先修正當前切片，不要擴大改動。
5. **下一切片或 review。** 行為完整後，再由 code review 決定是否有值得進行的重構；不要把大規模重構混在 red-green 迴圈中。

## WorldManagement 契約測試

- **領域與持久化**：驗證 rule 與 aggregate contract，僅依 storage interface；不得直接依賴 JDBC、SQL、YAML node 或檔案系統路徑。
- **指令**：異動可見 command 時，依 `docs/command-architecture.md` 測試 canonical `CommandRoute`、parser、alias、permission visibility 和 completion。completion 只能使用 `SuggestionCatalog`、不可變 metadata snapshot 與 `OnlinePlayerSnapshot`。
- **授權**：驗證 owner、rank、access-control 與 Warp 的允許/拒絕結果，玩家名稱只能由線上快照解析為 UUID。
- **非同步**：驗證工作移交與結果處理的邊界；不得在 command、listener 或 scheduler callback 內測試或加入 `get`、`join`。

## 驗證與回報

每個切片至少回報：red 指令與預期失敗、green 指令與結果、seam、涵蓋的行為與尚未涵蓋的失敗路徑。完成後依風險執行相鄰測試或 `./gradlew.bat check`，並說明無法執行的檢查。