---
name: diagnosing-bugs
description: "用於診斷 WorldManagement 的難重現 bug、錯誤、MSPT/TPS 回歸、非同步或持久化問題；以可重現的聚焦檢查驅動根因分析與回歸修復。"
argument-hint: "症狀、重現步驟、log、受影響功能或已知變更"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 問題診斷

此 skill 僅處理難重現、跨執行緒、效能或資料一致性問題。一般局部編譯錯誤應直接以最小修改與聚焦測試修正。

## 權威與限制

- `AGENTS.md`、現有 `docs/`、設定檔與程式碼是權威；此流程不得覆蓋它們。
- 先閱讀與症狀直接相關的文件、入口點、服務、測試及設定。`參考-舊設定/` 只能作為歷史輸入。
- 不得在 Paper 遊戲執行緒、指令、監聽器或 scheduler callback 建立阻塞診斷流程；不得以 `Future#get` 或 `join` 掩蓋問題。
- 不得直接修改正式 YAML、JDBC 資料、world 內容或其他插件設定以測試假設。I/O、audit 與備份工作必須遵守 `PluginIoExecutor` 邊界。

## 診斷流程

1. **建立回饋迴圈。** 優先建立能觀察使用者實際症狀的 JUnit 測試、聚焦 Gradle test、最小 fixture，或不改動資料的 server log/metrics 重現程序。回饋迴圈必須可由 agent 執行、可重複、明確 red/green，且越快越好；所有等待與外部程序必須有硬性期限。
2. **重現並最小化。** 先確認捕捉到的是原始症狀，再逐一移除非必要的輸入、設定、呼叫者與時間條件。保留最小、仍會失敗的情境。
3. **建立可推翻假設。** 至少列出三個依可能性排序的假設；每個都要寫成「若 X 為原因，改變或觀察 Y 時會看到 Z」。先將假設與最快的鑑別檢查提供給使用者。
4. **優先檢查專案高風險邊界。**
   - 阻塞 YAML、檔案、JDBC、audit、backup 或 directory delete 是否經 `PluginIoExecutor`。
   - 非同步完成後是否經 `WorldThreadDispatcher` 才讀寫 world、location 或 entity。
   - 權限、owner、rank、access-control 與 Warp 是否只讀不可變 metadata snapshot。
   - metadata repository 是否保持單一 provider、cache 是否有一致性或 shutdown race。
   - world 名稱與路徑是否通過核准容器的驗證。
5. **只做能鑑別假設的探針。** 優先使用 debugger、已存在的 metrics 或聚焦測試。必要的暫時 log 必須有唯一 `[DEBUG-<id>]` 前綴，不能記錄玩家敏感資料，並在修復前移除。
6. **先寫回歸測試再修復。** 將最小重現固定為透過公開服務、repository abstraction 或 command route 的測試；不要測 private implementation，也不要在測試中以 blocking wait 模擬 Paper callback。
7. **驗證並清理。** 重跑原始回饋迴圈與回歸測試，移除暫時探針與測試資料。環境允許時執行 `./gradlew.bat check`。驗證逾時時先終止測試擁有的子程序並收集 thread dump、JUnit report 或 Paper log；不得在沒有新診斷資訊或修改的情況下重跑同一命令。

## 效能問題

先量測，再修復。記錄基線與同一組 workload 下的結果；不要以新增大量 log 或在遊戲執行緒計時取代 profiling。涉及 MSPT、scheduler、lock、cache、JDBC、YAML 或已棄用 API 時，委派或要求使用 `Paper 效能審查員` 進行唯讀審查。

## 回報格式

1. **回饋迴圈**：實際執行的指令或測試、捕捉的症狀與重現性。
2. **最小重現與假設**：最小情境、排序後假設與鑑別結果。
3. **根因與修復**：控制流程、執行緒/I-O 邊界與最小修復。
4. **驗證與風險**：已執行結果、回歸測試、未能驗證的範圍及剩餘風險。