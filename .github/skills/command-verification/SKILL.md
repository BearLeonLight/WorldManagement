---
name: command-verification
description: "用於驗證 WorldManagement 單一command spec、編譯後指令樹、Help/usage、canonical routes、aliases、permissions、completion、Paper控制台與玩家端行為；依契約選擇JUnit、Paper console runtime或Mineflayer player E2E。"
argument-hint: "變更的指令、失敗行為、驗證範圍或 Paper JAR"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 指令驗證

以最低成本、足以推翻目標行為假設的測試層級驗證指令。`AGENTS.md`、`docs/commands.md`、`docs/command-architecture.md`、resource設定、`WorldManagementCommandSpec`、編譯後production tree與executable tests是權威；本skill只編排驗證，不維護平行指令清單或固定leaf數量。

## 測試路由

1. 先從使用者指定的指令、失敗測試或 `WorldManagementCommandSpec` 找出受影響的stable node ID、executable leaf、`CommandExecution`與可觀察結果；再確認compiler派生的canonical/root alias/module alias tree。
2. spec結構、compiler、Help/usage、parser、route、alias、permission visibility、completion、module enablement、domain rule或repository contract使用聚焦JUnit。completion只可依不可變snapshot；不得為測試新增離線玩家lookup。
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

- 從同一command spec編譯出的production tree列舉executable paths，對照測試宣告的canonical leaf ID；新增executable leaf卻沒有coverage declaration時視為失敗。
- 驗證Help topic、usage、permission/module visibility與completion均由同一spec派生；任何獨立Help command list、手寫usage catalog或平行Brigadier tree都視為契約違反。
- 分別回報 JUnit contract、console runtime 與 player runtime 覆蓋。不得把多個 response assertion 的數量宣稱為 leaf coverage。
- runtime 測試除訊息外，應驗證最接近契約的狀態，例如 metadata、world storage、quarantine、player world/location 或 shutdown marker。
- replacement/identity respawn驗證必須以server-side respawn或changed-world event及最終安全world作證。若world UUID replacement可能使vanilla個人spawnpoint失效，使用一次性的`PlayerRespawnEvent` fixture明確設定測試目的地，不得把不穩定的中間world或localized聊天訊息當成唯一證據。
- 測試失敗時先讀取對應 log：console matrix 使用 `build/console-command-test/latest.log`，player E2E 使用 `build/player-e2e/latest.log`，JAR smoke 使用 `build/paper-jar-smoke/latest.log`。
- protocol/decode 相容性問題才允許 player E2E 使用既有 Via fallback；spawn 後的指令、狀態或 shutdown 失敗不得切換 fallback 重試。
- 不得在 Paper thread 等待 future，或為測試繞過 `PluginIoExecutor`、`WorldThreadDispatcher`、mutation gate、權限與 snapshot 邊界。

## 回報格式

回報受影響 canonical leaves、每個 leaf 選用的最低測試層級、實際執行命令與結果、狀態 assertions、未涵蓋的 failure paths，以及未執行較高成本測試的理由。