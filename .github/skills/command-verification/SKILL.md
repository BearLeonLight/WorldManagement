---
name: command-verification
description: "用於驗證 WorldManagement 指令樹、canonical routes、aliases、permissions、completion、Paper 控制台執行與玩家端行為；依可觀察契約選擇 JUnit、Paper console runtime 或 Mineflayer player E2E，只有真實玩家身分、線上快照或遊戲內狀態不可替代時才使用 Mineflayer。"
argument-hint: "變更的指令、失敗行為、驗證範圍或 Paper JAR"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 指令驗證

以最低成本、足以推翻目標行為假設的測試層級驗證指令。`AGENTS.md`、`docs/commands.md`、`docs/command-architecture.md`、resource 設定、production command tree 與 executable tests 是權威；本 skill 只編排驗證，不維護平行指令清單或固定 leaf 數量。

## 測試路由

1. 先從使用者指定的指令、失敗測試或 `BrigadierWorldManagementCommand` 找出受影響的 executable leaf、canonical `CommandRoute` 與可觀察結果。
2. 純 parser、route、alias、permission visibility、completion、module enablement、domain rule 或 repository contract 使用聚焦 JUnit。completion 只可依不可變 snapshot；不得為測試新增離線玩家 lookup。
3. 必須載入實際 Paper/plugin，但結果可由 console sender、log、metadata 或 filesystem state 完整證明時，使用 `paperConsoleCommandTest`。不得只為了「黑箱」標籤啟動 Mineflayer。
4. 只有契約依賴真實玩家 sender、登入後 permission/context、`OnlinePlayerSnapshot` 名稱解析、player/entity thread affinity、傳送位置、世界切換、fallback relocation 或玩家互動時，使用 `paperPlayerE2eTest`。
5. `paperJarSmokeTest` 只驗證啟動、基本 console command 與正常 shutdown，不取代 console command matrix 或 player E2E。
6. 同一 leaf 可有多層測試；低層 contract test 與 runtime test 證明不同風險，不以 runtime outcome 數量取代 executable-leaf coverage。

## 執行順序

1. 執行最窄的相關 JUnit 測試，例如：
   `./gradlew.bat test --tests "io.github.bearl.worldmanagement.command.BrigadierWorldManagementCommandTest"`
2. 需要實際 Paper console 行為時執行：
   `./gradlew.bat paperConsoleCommandTest`
3. 需要真實玩家契約時才執行：
   `./gradlew.bat paperPlayerE2eTest`
4. 需要啟動封裝 smoke 時執行：
   `./gradlew.bat paperJarSmokeTest`
5. 完成程式碼變更後，在環境允許時執行：
   `./gradlew.bat check`

Paper JAR 選擇沿用 Gradle 的 `-PpaperServerJar=<path>`、`-PpaperServerSource=local|download` 與 `-PpaperDownloadChannel=STABLE|BETA|ALPHA`，不得在 skill 內重作下載或版本判斷。

## 覆蓋與失敗處理

- 以 command tree 的 executable paths 對照測試宣告的 canonical leaf ID；新增 executable leaf 卻沒有 coverage declaration 時視為失敗。
- 分別回報 JUnit contract、console runtime 與 player runtime 覆蓋。不得把多個 response assertion 的數量宣稱為 leaf coverage。
- runtime 測試除訊息外，應驗證最接近契約的狀態，例如 metadata、world storage、quarantine、player world/location 或 shutdown marker。
- 測試失敗時先讀取對應 log：console matrix 使用 `build/console-command-test/latest.log`，player E2E 使用 `build/player-e2e/latest.log`，JAR smoke 使用 `build/paper-jar-smoke/latest.log`。
- protocol/decode 相容性問題才允許 player E2E 使用既有 Via fallback；spawn 後的指令、狀態或 shutdown 失敗不得切換 fallback 重試。
- 不得在 Paper thread 等待 future，或為測試繞過 `PluginIoExecutor`、`WorldThreadDispatcher`、mutation gate、權限與 snapshot 邊界。

## 回報格式

回報受影響 canonical leaves、每個 leaf 選用的最低測試層級、實際執行命令與結果、狀態 assertions、未涵蓋的 failure paths，以及未執行較高成本測試的理由。