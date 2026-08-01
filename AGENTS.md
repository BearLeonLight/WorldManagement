# WorldManagement Agent 指引

## 基本規則

- 使用 Java 25；Gradle toolchain 與 compiler release 必須維持 25。
- 非遊戲工作優先使用 Java 標準函式庫；遊戲工作使用 Paper API；僅在 Paper 無合適 API 時使用 Bukkit。
- 不得使用標記為 `@Deprecated` 的 API。
- 保留既有套件結構，公開 API 保持精簡。

## 執行緒與 I/O

- Paper 遊戲執行緒不得執行阻塞的檔案、YAML、JDBC、稽核、備份或目錄刪除工作。
- 阻塞工作必須經 bounded `PluginIoExecutor`；queue 滿載或 shutdown 後的 submission 必須以 exceptional future 明確拒絕，不得阻塞、無界累積或在 caller thread 執行。指令、監聽器與 Paper scheduler callback 不得呼叫 `Future#get`、`join` 或進行阻塞等待。
- 非同步完成處理器存取 Bukkit/Paper 的 world、location 或 entity 前，必須使用 `WorldThreadDispatcher`。
- 權限、access-control、rank 與 Warp 檢查只能讀取不可變的記憶體快照。
- shutdown 必須先停止 lifecycle admission並取消 plugin-owned scheduler/runtime pending operation，再以 future 串接有界排空；已提交的Paper operation必須將command result與底層runtime drain分離，不能以wrapper future完成宣稱operation已結束。任一`beginShutdown()`同步失敗或回傳null時，仍必須交由shutdown coordinator完成terminal close。repository、audit與診斷資源只能由 I/O worker的 terminal action 關閉，不得建立未受管 drain thread或在 Paper thread等待。

## 持久化與安全

- 應用程式碼只能相依儲存介面，不得相依 JDBC、SQL、YAML node 或檔案系統路徑。
- 僅能啟用一個 metadata provider：YAML、SQLite 或 MySQL/MariaDB；不得引入隱式雙寫。
- storage migration 必須與所有 metadata mutation 共用 gate；成功驗證後保持 mutation frozen直到切換provider並重啟，失敗則清除部分target並重新開放active provider mutation。
- world 名稱必須符合 `^[a-zA-Z0-9_-]+$`，且正規化後的路徑必須仍為核准容器的直接子項目。
- `adopt` 只能為已載入且未註冊的 world 建立 WorldManagement metadata；不得變更 world 內容或其他插件設定。
- world delete 必須先建立可回復quarantine claim，再持久化 `DELETING` tombstone，之後才能永久刪除檔案；啟動恢復必須依tombstone判斷完成刪除或還原。

## 品質

- 為變更的領域規則與持久化契約新增聚焦測試。
- 環境允許時，程式碼變更後執行 `./gradlew.bat check`。
- 可觀察的指令、設定或行為變更時，更新 README 與相應的 `docs/` 契約。

## 指令契約

- 唯一 canonical root 是 `/wm`，`WorldManagementCommandSpec` 是指令結構的唯一來源。lifecycle 只能位於它的完整 tree 下；Warp、Ownership、Storage 的 alias 必須由 `commands.yml` 設定並經 Paper `PluginBootstrap` 從同一 spec 編譯。
- canonical/root alias/module alias tree、Help、usage/error、permission/module metadata、completion binding、`CommandRoute` execution 與runtime leaf IDs必須由同一spec派生；不得另建手寫Brigadier tree、Help command list或usage catalog。
- `CommandRoute` 只能使用 Brigadier 已解析的 arguments；不得依原始 command text、command label 或 `split()` 路由。
- 每個 command spec node 必須有 module enablement 與對應 permission visibility；參數相依的 world/owner/rank/access 授權保留在 domain handler。
- completion 只能使用 `SuggestionCatalog`、不可變 metadata snapshot 和 `OnlinePlayerSnapshot`。禁止在 completion 執行 YAML/JDBC/檔案 I/O、等待 future，或讀取可變 Bukkit world/location/entity。
- 新增或修改可見指令時，先更新唯一command spec，再同步domain handler、`commands.yml` alias/help schema、`paper-plugin.yml`權限、stable node ID對應locale、README、`docs/commands.md`、`docs/command-architecture.md`、runtime coverage與聚焦spec/compiler/parser/alias/access/completion/Help tests。
- 玩家名稱參數只可由線上快照解析為 UUID；metadata 永遠保存 UUID，不得在指令或 completion 做離線玩家 lookup。

## 來源文件

`參考-舊設定/` 是歷史輸入，不是權威實作契約。已確認的規格應在實作時遷移至 `docs/`。

## 子代理探索

- 跨套件、跨持久化層、執行緒親和性、效能風險，或無法以局部搜尋確認控制流程時，可主動使用唯讀子代理探索或獨立審查。
- 子代理適合平行蒐集事實、定位入口點、找出相關測試及識別風險；主代理必須以實際檔案與聚焦驗證確認結論，並負責最終修改。
- 單一檔案、小型局部修正或已知失敗測試的直接修復，不必啟動子代理。
- 除非使用者明確要求委派實作且主代理可驗證結果，否則子代理不得修改專案檔案。

## 依需求執行的工作流程

- clone、換電腦、恢復工作或完成重大變更後，使用 `/project-handover` 產生唯讀的繁體中文專案交接報告。
- 完成變更後，使用 `/project-health` 檢查實作、文件、測試與已知風險，不修改專案檔案。

## Agent Skills

- `AGENTS.md`、`docs/`、resource 設定與程式碼是唯一權威；`.github/skills/` 的流程不得建立平行規格或覆蓋這些契約。
- 一般新增或修改功能應優先使用 `.github/prompts/` 的專案工作流程；`diagnosing-bugs`、`tdd`、`code-review` 與 `research` 僅用於其描述的專門情境。
- 涉及 Paper 執行緒、阻塞 I/O、MSPT/TPS、scheduler、JDBC、cache、lock 或已棄用 API 的 review，使用 `Paper 效能審查員` 補充唯讀審查。
- 技術研究以本 workspace 的 Paper、Paper-docs、LuckPerms、Adventure 與官方文件為準；需要保存的研究結果寫入 `docs/research/`，架構決策只在必要時新增至 `docs/adr/`。
