# WorldManagement

WorldManagement 是面向 Paper 伺服器的世界管理插件，目標是提供安全的世界生命週期管理、世界內所有權與保護，以及 Warp 管理，同時避免在遊戲執行緒進行阻塞 I/O。

> 專案目前提供安全的受管世界 lifecycle、world identity replacement隔離與恢復、fallback player relocation、快取式保護、Warp、owner/rank/access 管理、audit policy，以及 YAML、SQLite、MySQL/MariaDB metadata provider。LuckPerms 為可選整合；安裝且啟用時，Warp外部權限會使用目的世界context的cached permission，未安裝時安全拒絕需要此外部權限的Warp。

## 系統需求

- Java 25 LTS
- 與 `paper-plugin.yml` 宣告版本相容的 Paper 伺服器
- Windows、Linux 或 macOS

## 建置與測試

```powershell
.\gradlew.bat check
.\gradlew.bat build
```

除了單元測試，`paperJarSmokeTest` 會在隔離的 `build/paper-jar-smoke/` 目錄啟動實際 Paper server、安裝 shadow JAR、確認 WorldManagement 載入 metadata、執行 `wm list` 後正常停止。無參數執行時會優先使用相鄰 `TestServer/` 目錄最新修改的 `paper-*.jar`；找不到本機 JAR 時，會從 Paper 官方 Fill v3 API 下載與 `paperApiVersionDeclaration` 相符的版本，驗證 SHA-256，並快取於 Gradle user home。

```powershell
.\gradlew.bat paperJarSmokeTest
```

可用 `-PpaperServerJar=<path>` 指定 JAR，或用 `-PpaperServerSource=local|download` 強制只使用 TestServer 或官方下載。下載 channel 預設由 `paperApiVersion` qualifier 決定（stable、beta 或 alpha），也可用 `-PpaperDownloadChannel=STABLE|BETA|ALPHA` 覆寫：

```powershell
.\gradlew.bat paperJarSmokeTest -PpaperServerSource=download
.\gradlew.bat paperJarSmokeTest -PpaperServerJar="E:\Minecraft\paper.jar"
```

測試完整伺服器輸出會保留在 `build/paper-jar-smoke/latest.log`，供啟動失敗時檢查。一般 `check` 不會隱式下載或啟動伺服器，確保離線單元測試仍可執行。

不需要玩家身分或遊戲內狀態的 Paper runtime 指令，使用獨立的控制台矩陣。此 task 不執行 `npm ci`，不啟動 Mineflayer，也不安裝 Via；它會驗證 29 個 console runtime leaves、42 個 console command outcomes、root/module aliases、world storage、metadata、identity recovery、quarantine、migration target 與正常 shutdown：

```powershell
.\gradlew.bat paperConsoleCommandTest
```

需要玩家 sender、線上玩家名稱快照、實際位置、世界傳送、保護事件、identity隔離或fallback relocation 時，才手動執行選用的Mineflayer測試。此task會在`e2e/player/`執行`npm ci`，先啟動不含跨版本插件的隔離offline-mode Paper server，透過status ping取得實際Minecraft version/protocol，並在Mineflayer支援時直接使用對應原生版本登入。只有版本不在Mineflayer `testedVersions`，或spawn前發生明確protocol/decode相容性錯誤時，才會停止原生attempt、從Hangar按需下載並SHA-256驗證ViaVersion/ViaBackwards 5.11.0，再重啟fallback attempt。成功spawn後的指令、操作與shutdown失敗不會改用Via重試。玩家流程驗證15個必須有玩家的runtime leaves與27個player command outcomes，另以Paper event、dimension、metadata與block final state驗證break/place/interact/container、identity replacement及post-respawn relocation；純console flow不在此task重複執行。此task也會使用`-PluckPermsPluginJar=<path>`指定的LuckPerms JAR、相鄰LuckPerms workspace的最新Bukkit JAR，或從Modrinth取得固定5.5.53 artifact並驗證SHA-512，以真實LuckPerms服務驗證Warp目的世界context：

```powershell
.\gradlew.bat paperPlayerE2eTest
```

Paper JAR 選擇參數與 `paperJarSmokeTest` 相同。Via fallback 預設動態使用目前 Mineflayer `testedVersions` 的最新版，也可用 `-PmineflayerMinecraftVersion=<version>` 只覆寫 fallback client；原生模式永遠使用 status ping 對應版本。必要的 `paperJarSmokeTest` 固定使用隔離 SQLite provider，console matrix 與玩家 E2E 使用 YAML provider；三者都會要求 terminal repository/audit/diagnostic close 的成功 marker，並將 classloader、I/O drain 或 shutdown timeout warning 視為失敗。player suite由Paper console的dimension與事件探針驗證實際registry world key、傳送、respawn及保護結果，不只比較Mineflayer快取或localized聊天文字。console log 保留在 `build/console-command-test/latest.log`；player attempt logs 分別保留在 `build/player-e2e/native/latest.log` 與 `build/player-e2e/via-fallback/latest.log`，成功 attempt 另複製為 `build/player-e2e/latest.log`。Via與LuckPerms artifacts都快取於Gradle user home；console與原生協定判斷不會取得Via artifacts。

`e2e/command-runtime-coverage.txt` 將每個 Brigadier executable path 分類為 `CONSOLE_RUNTIME` 或 `PLAYER_RUNTIME`。JUnit 會把這份宣告與實際 command tree 做完整集合比對；新增 leaf 卻未分類、重複 path 或未知層級都會讓 `check` 失敗。兩個 runtime runner 也會分別驗證自己實際執行的 canonical leaf IDs，並在發送前比對 command literal skeleton、argument arity 與 aliases；不能以訊息 assertion 數量代替 leaf coverage。runtime task 會先執行純 Node harness tests，確認 leaf mapping 與 build child 目錄 guard，避免錯誤環境變數刪除 build 外路徑。

可部署的 shaded JAR 位於 `build/libs/`，已內含 BoostedYAML、SQLite 與 MySQL JDBC driver，並 relocate BoostedYAML/SnakeYAML。將該 JAR 放入 Paper 伺服器的 `plugins/` 目錄，重新啟動伺服器。

## 基本操作範例

建立並管理一個一般世界，設定顯示名稱，再將自己傳送進去：

```text
/wm create survival NORMAL NORMAL
/wm display-name set survival <green>生存世界</green>
/wm tp self survival
```

將已由Paper或其他世界工具載入的世界納入管理，以及將磁碟上具備`level.dat`的安全世界目錄匯入：

```text
/wm adopt events
/wm import archive NORMAL
```

站在目標世界建立公開Warp，讓玩家使用；私有Warp可用`trust`加入目前線上玩家名稱或UUID：

```text
/wm warp set survival spawn PUBLIC
/wm warp tp survival spawn
/wm warp trust survival staff-room add PlayerName
```

卸載與永久刪除是不同操作。`unload`保留metadata與世界資料：

```text
/wm unload survival world
```

對仍載入的世界執行`delete`時，第一次會搬離玩家、存檔並卸載；管理員確認狀態後必須再次執行相同指令才會永久刪除。若世界原本已卸載，第一次confirmed delete就會進入永久刪除：

```text
/wm delete survival world confirm
/wm delete survival world confirm
```

當`/wm identity show <world>`回報`SYNC_PENDING`時使用`identity sync`接受非durable snapshot drift；回報`CONFLICT`代表觀察到replacement world，該世界會被隔離，需明確選擇是否保留舊Warp。無法接受replacement時可用`abandon`停止管理並保留metadata：

```text
/wm identity show survival
/wm identity sync survival
/wm identity accept-replacement survival confirm clear-warps
/wm identity abandon survival confirm
```

完整權限、狀態前提與所有語法見[指令](docs/commands.md)。

## 啟動診斷

WorldManagement 在 `INFO` 層級輸出精簡的啟動階段摘要：設定與 storage、metadata 數量、啟用模組與可選 LuckPerms capability，以及總啟動耗時。例如：

```text
[WorldManagement] Enabling WorldManagement 0.1.0...
[WorldManagement]     Loading configuration and storage...
[WorldManagement]     Configuration and storage loaded: YAML metadata storage, locale zh_TW. Took 18ms
[WorldManagement]     Storage
[WorldManagement]         Metadata provider: YAML
[WorldManagement]         Locale messages: zh_TW
[WorldManagement]         Audit policy: BEST_EFFORT
[WorldManagement]         Audit backend: JSONL
[WorldManagement]     Modules
[WorldManagement]         lifecycle: enabled
[WorldManagement]         warp: enabled
[WorldManagement]         ownership: enabled
[WorldManagement]         protection: enabled
[WorldManagement]         storage: enabled
[WorldManagement]     Loading world metadata...
[WorldManagement]     World metadata loaded: 2 managed worlds. Took 31ms
[WorldManagement]     Metadata
[WorldManagement]         World creative: 3 ranks, WHITELIST access, 8 assigned players, 2 warps
[WorldManagement]         World survival: 2 ranks, NONE access, 0 assigned players, 0 warps
[WorldManagement]     Initializing hooks and services...
[WorldManagement]     Hooks
[WorldManagement]         LuckPerms: available
[WorldManagement]     Services
[WorldManagement]         Command service: /wm Brigadier tree initialized
[WorldManagement]         Suggestion service: metadata and online-player snapshots initialized
[WorldManagement]         Audit service: BEST_EFFORT policy, JSONL backend
[WorldManagement]         Online player snapshot listener registered
[WorldManagement]         Protection listener registered
[WorldManagement]     Services ready: commands, suggestions, audit, and listeners initialized. Took 6ms
[WorldManagement] Enabled WorldManagement 0.1.0 with YAML metadata storage. Took 55ms
```

啟動日誌會列出每個受管世界的名稱及非敏感統計，但不會輸出資料庫 URL、帳密、玩家名稱或 UUID。可選整合會獨立顯示在 `Hooks`，不與服務初始化混在同一行。

## 除錯診斷

`config.yml` 的 `debug` 區段只控制 WorldManagement 自身的額外診斷，不修改 Paper、其他插件或全域 Log4j 等級。支援 `OFF`、`BASIC`、`VERBOSE`，可獨立輸出至標準 Paper console/`latest.log` 與 `plugins/WorldManagement/logs/debug.log`。專用檔案由 bounded 背景 writer 寫入並支援大小輪替與保留份數；queue 壓力只會捨棄 best-effort 診斷，不會阻塞世界操作、metadata 或 audit。

診斷可依 `STARTUP`、`METADATA`、`AUDIT`、`IO`、`COMMAND`、`LIFECYCLE`、`WARP`、`OWNERSHIP`、`PROTECTION`、`STORAGE` area 篩選。空清單代表不輸出任何診斷。指令只記 canonical action 與已解析的安全欄位，不保存 raw command；protection 高頻事件以 30 秒窗口彙總。完整設定、隱私與輪替契約見 [設定與 metadata](docs/configuration.md)。

## 目標功能

- 已實作：adopt、create、load、unload、remove/detach、manage、detached list/purge、import、delete confirm、world tp、storage migrate confirm
- 每世界 metadata、記憶體快取與安全路徑驗證；拒絕 symbolic link、Windows junction/reparse point 與其他特殊 filesystem entry
- 世界 owner、rank、access-control 與快取式互動保護
- 公開/私有 Warp 與線上玩家名稱或 UUID trust（metadata 一律保存 UUID）
- 同一世界 lifecycle state gate、fallback world 驗證與 entity-affine 非同步玩家傳送
- metadata-first desired state、啟動/外部載入 bounded reconciliation、顯式存檔後卸載、已載入世界的兩階段 delete confirm、nonblocking delete delay、`DELETING` tombstone、atomic quarantine/restore、同一 runtime 永久刪除、crash recovery 與 external reload abort；第二次確認正常會直接清除世界檔案與 metadata，只有 tombstone 後的永久刪除或 metadata purge 問題才延後至下次啟動 recovery
- YAML、SQLite、MySQL/MariaDB metadata provider，且永遠只有一個有效 provider；SQL 使用 HikariCP
- YAML atomic write、備份、毀損隔離；JSONL rotation 或 SQL audit store
- SQL metadata mutation 與其成功 audit event 使用同一 JDBC transaction；YAML provider 保持 metadata 原子檔案寫入後的非阻塞 JSONL audit
- `/wm` 採 Paper Brigadier + `PluginBootstrap` command tree；`commands.yml` 可設定 root/module aliases，並保留原生 permission-aware completion
- `modules.yml` 提供 lifecycle、warp、ownership、protection、storage 的獨立功能開關；停用模組時其 command branch 與 listener 不會啟用
- `OFF`、`BEST_EFFORT`、`STRICT` audit policy；破壞性操作在 strict audit 無法排入時會拒絕
- bounded單一I/O worker、migration期間metadata mutation freeze/target rollback，以及不阻塞Paper thread的event-driven shutdown；terminal resource close 由有硬上限的受管 daemon worker 逐項隔離，既有 I/O 或 close 忽略 interrupt 時仍會嘗試後續資源，逾時 future 會明確失敗
- WorldManagement 專用 `OFF/BASIC/VERBOSE` 診斷、area allowlist、Paper console 與 bounded rotating file sink
- 可選 LuckPerms Warp外部權限整合；以已載入且identity相符的目的Bukkit world建立cached permission context，玩家名稱仍只由線上快照解析，WorldManagement rank不映射為LuckPerms group
- `messages_zh_TW.yml` 使用 Adventure MiniMessage，集中管理全部指令回覆、可選共用前綴與逐語意訊息 key
- 玩家與 RCON 接收 Adventure Component；本機控制台以固定 ANSI 16 色呈現啟動摘要與指令回覆，Paper 檔案 log 保持純文字
- 自訂 locale 缺少的 key 會從 JAR 內建 template 自動補入並保存；無效 key 只在執行時回退，不覆寫管理員內容
- `hooks.yml` 預設資源，設定啟動時會驗證 hook

MySQL/MariaDB 的 adapter 可使用 [設定與 metadata](docs/configuration.md) 中的 property-gated integration test，對專用測試資料庫做實際連線驗證。

完整架構計畫與目前交付差異請參考 [實作狀態](docs/implementation-status.md)。

## 外部世界工具

WorldManagement 只管理明確登錄的世界。`/wm adopt <world>` 只會為已載入世界建立本插件的 metadata；不改變地圖檔、載入狀態或 Multiverse 等外部工具設定。未登錄世界不套用保護、Warp 或刪除操作。

## 文件

- [AI 協作規範](AGENTS.md)
- [架構](docs/architecture.md)
- [指令](docs/commands.md)
- [指令架構](docs/command-architecture.md)
- [設定與 metadata](docs/configuration.md)
- [實作狀態](docs/implementation-status.md)
- [舊版歷史參考](參考-舊設定/)
