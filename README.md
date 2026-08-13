# WorldManagement

WorldManagement 是面向 Paper 伺服器的世界管理插件，目標是提供安全的世界生命週期管理、世界內所有權與保護，以及 Warp 管理，同時避免在遊戲執行緒進行阻塞 I/O。

目前正式版本為 `1.0.0`，也是插件設定、YAML metadata 與 JDBC schema 的初版契約。

> 專案目前提供安全的受管世界 lifecycle、world identity replacement隔離與恢復、fallback player relocation、快取式保護、Warp、owner/rank/access 管理、audit policy，以及 YAML、SQLite、MySQL/MariaDB metadata provider。LuckPerms、Multiverse-Core 5、PlaceholderAPI與MiniPlaceholders為可選整合；兩套placeholder provider以短namespace `wm`公開只讀世界snapshot。

## 系統需求

- Java 25 LTS
- Minecraft `26.x`（`1.26.x`）的 Paper 伺服器；WorldManagement 會在載入階段檢查遊戲版本，其他版本會記錄不支援提示並停用插件，不會初始化 storage、listener、服務或處理命令
- 可選的Multiverse-Core lifecycle整合需要5.2.0以上版本；5.0.x缺少保留Bukkit runtime的安全untracking API
- Windows、Linux 或 macOS

## 建置與測試

```powershell
.\gradlew.bat check
.\gradlew.bat build
```

自動化驗證使用有界時間預算：JUnit每個測試預設10秒且`test` task最多3分鐘；`paperJarSmokeTest`最多3分鐘。console與player E2E會先用獨立且最多3分鐘的task準備Paper artifact，再分別以7與11分鐘執行Node runner；runner本身會在6與10分鐘先停止Paper、等待輸出pipe關閉並關閉log stream。Paper與E2E資產下載也有連線/讀取期限。逾時應視為可診斷的測試失敗，先讀取JUnit報告或下列runtime log，不應直接提高期限或無修改重跑。

`clean`只會移除專案`build/`中的編譯產物、報告與隔離 Paper test server，保留可重用的 Node E2E dependencies 和 Gradle user-home downloads。需要重置這些可重建資料或釋放磁碟空間時，個別執行下列 opt-in tasks：

```powershell
.\gradlew.bat clean
.\gradlew.bat cleanE2eDependencies
.\gradlew.bat cleanWorldManagementE2eCache
```

`cleanE2eDependencies`會移除console與player E2E的`node_modules`；後續E2E task會依既有流程重新執行`npm ci`。`cleanWorldManagementE2eCache`只會移除Gradle user home的`caches/worldmanagement/`，也就是本專案下載並驗證的Paper、Via、LuckPerms與placeholder provider artifacts；它不會刪除一般Gradle dependency cache、wrapper或相鄰`TestServer/`的JAR。

除了單元測試，`paperJarSmokeTest` 會在隔離的 `build/paper-jar-smoke/` 目錄啟動實際 Paper server、安裝 shadow JAR、確認 WorldManagement 載入 metadata、執行 `wm list` 後正常停止。無參數執行時會優先使用相鄰 `TestServer/` 目錄最新修改的 `paper-*.jar`；找不到本機 JAR 時，會從 Paper 官方 Fill v3 API 下載與 `paperApiVersionDeclaration` 相符的版本，驗證 SHA-256，並快取於 Gradle user home。

```powershell
.\gradlew.bat paperJarSmokeTest
```

可用 `-PpaperServerJar=<path>` 指定 JAR，或用 `-PpaperServerSource=local|download` 強制只使用 TestServer 或官方下載。下載 channel 預設由 `paperApiVersion` qualifier 決定（stable、beta 或 alpha），也可用 `-PpaperDownloadChannel=STABLE|BETA|ALPHA` 覆寫：

```powershell
.\gradlew.bat paperJarSmokeTest -PpaperServerSource=download
.\gradlew.bat paperJarSmokeTest -PpaperServerJar="E:\Minecraft\paper.jar"
```

同時提供兩套正式plugin JAR時，smoke task也會要求兩個`wm` provider狀態為`available`，並分別透過PAPI與MiniPlaceholders的官方parse command驗證global與指定世界placeholder。兩個參數必須成對提供；artifact來源與checksum須由呼叫端固定：

```powershell
.\gradlew.bat paperJarSmokeTest `
	-PplaceholderApiPluginJar="E:\Minecraft\PlaceholderAPI-2.12.3.jar" `
	-PminiPlaceholdersPluginJar="E:\Minecraft\MiniPlaceholders-Paper-3.2.0.jar"
```

測試完整伺服器輸出會保留在 `build/paper-jar-smoke/latest.log`，供啟動失敗時檢查。一般 `check` 不會隱式下載或啟動伺服器，確保離線單元測試仍可執行。

不需要玩家身分或遊戲內狀態的 Paper runtime 指令，使用獨立的控制台矩陣。此 task 不啟動 Mineflayer，也不安裝 Via；它會安裝固定且SHA-256驗證的Multiverse-Core 5.7.3，驗證33個console runtime leaves、72個console command outcomes、Help與巢狀錯誤導引、所有loaded runtime world清單、unknown runtime unload/delete、同runtime永久刪除、MV remove/delete untracking與重啟持久性、root/module aliases、world storage、metadata、identity recovery、quarantine、migration target與正常shutdown。可用`-PmultiversePluginJar=<path>`覆寫MV JAR：

```powershell
.\gradlew.bat paperConsoleCommandTest
```

需要玩家 sender、線上玩家名稱快照、實際位置、世界傳送、保護事件、identity隔離或fallback relocation 時，才手動執行選用的Mineflayer測試。此task會在`e2e/player/`執行`npm ci`，以`-Xms512M -Xmx1024M`啟動不含跨版本插件的隔離offline-mode Paper server，透過status ping取得實際Minecraft version/protocol，並在Mineflayer支援時直接使用對應原生版本登入。只有版本不在Mineflayer `testedVersions`，或spawn前發生明確protocol/decode相容性錯誤時，才會停止原生attempt、從Hangar按需下載並SHA-256驗證ViaVersion/ViaBackwards 5.11.0，再重啟fallback attempt。成功spawn後的指令、操作與shutdown失敗不會改用Via重試。玩家流程驗證15個player runtime leaves與33個player command outcomes，另以Paper event、dimension、metadata與block final state驗證loaded remove、DETACHED治理與tp、unknown fallback/delete、break/place/interact/container、identity replacement及post-respawn relocation；identity replacement重啟階段會停用Protection模組，確認lifecycle isolation仍獨立運作。此task也會使用`-PluckPermsPluginJar=<path>`指定的LuckPerms JAR、相鄰LuckPerms workspace的最新Bukkit JAR，或從Modrinth取得固定5.5.53 artifact並驗證SHA-512，以真實LuckPerms服務驗證Warp目的世界context：

```powershell
.\gradlew.bat paperPlayerE2eTest
```

Paper JAR 選擇參數與 `paperJarSmokeTest` 相同。Via fallback 預設動態使用目前 Mineflayer `testedVersions` 的最新版，也可用 `-PmineflayerMinecraftVersion=<version>` 只覆寫 fallback client；原生模式永遠使用 status ping 對應版本。必要的 `paperJarSmokeTest` 固定使用隔離 SQLite provider，console matrix 與玩家 E2E 使用 YAML provider；三者都會要求 terminal repository/audit/diagnostic close 的成功 marker，並將 classloader、I/O drain、shutdown timeout warning或Paper watchdog stall視為失敗。player suite的world fixtures使用固定seed、停用structures與forced spawn，避免把vanilla隨機spawn搜尋延遲混入指令契約，並由Paper console的dimension與事件探針驗證實際registry world key、傳送、respawn及保護結果，不只比較Mineflayer快取或localized聊天文字。replacement respawn案例會以一次性的`PlayerRespawnEvent`測試目的地明確進入CONFLICT world，再確認production post-respawn relocation回到安全fallback；不依賴replacement UUID後可能失效的vanilla個人spawnpoint。console log 保留在 `build/console-command-test/latest.log`；player attempt logs 分別保留在 `build/player-e2e/native/latest.log` 與 `build/player-e2e/via-fallback/latest.log`，成功 attempt 另複製為 `build/player-e2e/latest.log`。Via與LuckPerms artifacts都快取於Gradle user home；console與原生協定判斷不會取得Via artifacts。

`e2e/command-runtime-coverage.txt` 將每個 Brigadier executable path 分類為 `CONSOLE_RUNTIME` 或 `PLAYER_RUNTIME`。JUnit 會把這份宣告與實際 command tree 做完整集合比對；新增 leaf 卻未分類、重複 path 或未知層級都會讓 `check` 失敗。兩個 runtime runner 也會分別驗證自己實際執行的 canonical leaf IDs，並在發送前比對 command literal skeleton、argument arity 與 aliases；不能以訊息 assertion 數量代替 leaf coverage。runtime task 會先執行純 Node harness tests，確認 leaf mapping 與 build child 目錄 guard，避免錯誤環境變數刪除 build 外路徑。

可部署的 shaded JAR 位於 `build/libs/WorldManagement-1.0.0.jar`，已內含 BoostedYAML、SQLite 與 MySQL JDBC driver，並 relocate BoostedYAML/SnakeYAML。將該 JAR 放入 Paper 伺服器的 `plugins/` 目錄，重新啟動伺服器。

## 基本操作範例

查看目前有權使用且所屬模組已啟用的指令，或查詢完整 canonical command path：

```text
/wm help
/wm help 2
/wm help ownership rank set
```

建立並管理一個一般世界，設定顯示名稱，再將自己傳送進去：

```text
/wm create survival NORMAL NORMAL
/wm create terrain NORMAL NORMAL --generator Terra:normal --seed 8675309
/wm create void-events NORMAL FLAT --generator Terra:void --generator-settings '{"preset":"events"}' --no-structures --biome Terra:climate --force-spawn-position 0,80,0,90,0
/wm create starter NORMAL NORMAL --generate-bonus-chest
/wm display-name set survival <green>生存世界</green>
/wm tp self survival
```

create flags可任意排序且不得重複。completion逐token提供尚未使用的flag；選取`--generator`後只在下一個token補全provider，不會把`--generator Terra:normal --seed 8675309`組成單一候選。`--generate-bonus-chest`與`--force-spawn-position`互斥；generator/biome provider無法由啟用中的插件解析時會安全拒絕，不會退回vanilla provider。generator與biome provider reference會保存至metadata，供後續managed load重新解析；其餘選項是交給Paper的建立時資料。MV的alias、game mode、difficulty、auto-load、world price與portal設定不屬於世界生成輸入，不由此指令模擬。

將已由Paper或其他世界工具載入的世界納入管理，以及將磁碟上具備`level.dat`的安全世界目錄匯入：

```text
/wm adopt events
/wm import archive NORMAL
/wm adopt events-staged --detached
/wm import archive-staged NORMAL --detached
/wm load archive-cold NORMAL --detached
```

`--detached`只登錄 metadata 與 lifecycle 工具，不啟用 Warp、Ownership 或 Protection 治理；DETACHED仍可使用world tp與display-name。若要讓目前受管世界停止治理，但保留 loaded runtime、玩家、metadata、設定與地圖，使用 `/wm remove <world>`；之後可用 `/wm manage <world>` 重新啟用。只有 `purge confirm` 會移除 DETACHED metadata，只有 `delete ... confirm` 會刪除地圖資料。

站在目標世界建立公開Warp，讓玩家使用；私有Warp可用`trust`加入目前線上玩家名稱或UUID：

```text
/wm warp set survival spawn PUBLIC
/wm warp tp survival spawn
/wm warp trust survival staff-room add PlayerName
```

卸載與永久刪除是不同操作。`unload`保留metadata與世界資料。fallback可為任一不同、唯一且已載入的runtime world，不要求先由WorldManagement登錄：

```text
/wm unload survival world
```

`unload`與`delete`的world completion會列出ACTIVE、DETACHED及目前唯一載入的unknown world；每次指令仍只操作一個指定世界，不是批次刪除。unknown loaded world執行unload時不建立metadata；執行delete時會先從exact runtime identity建立`DELETE_AUTO` DETACHED metadata，再走相同的安全刪除流程。

`/wm list all`列出目前所有唯一載入的runtime world，內建繁中會標示「受管」、「已停止管理」或「未知」。只有runtime identity與metadata完全相符時才會標示受管或已停止管理；replacement、identity衝突及無metadata的世界都顯示未知。持久化與診斷使用的機器值仍為`ACTIVE`、`DETACHED`與`UNKNOWN`。此清單只讀事件維護的不可變snapshot，不讀取Bukkit world、YAML或資料庫。

對仍載入的世界執行`delete`時，第一次會搬離玩家、存檔並卸載；管理員確認狀態後必須再次執行相同指令。第二次會先要求Multiverse-Core停止追蹤，接著將storage移入quarantine、寫入`DELETING` tombstone，並在同一runtime永久刪除storage與metadata。若plugin已進入shutdown，durable tombstone與quarantine會保留，由下一次啟動recovery接續：

```text
/wm delete survival world confirm
/wm delete survival world confirm
```

當`/wm identity show <world>`回報「等待同步」時使用`identity sync`接受非durable snapshot drift；回報「衝突」代表觀察到replacement world，該世界會被隔離，需明確選擇是否保留舊Warp。對應的持久化機器值分別為`SYNC_PENDING`與`CONFLICT`。無法接受replacement時可用`abandon`停止管理並保留metadata：

```text
/wm identity show survival
/wm identity sync survival
/wm identity accept-replacement survival confirm clear-warps
/wm identity abandon survival confirm
```

完整權限、狀態前提與所有語法見[指令](docs/commands.md)。

## Placeholders

安裝並在`hooks.yml`啟用PlaceholderAPI或MiniPlaceholders後，WorldManagement會向各自API註冊`wm` provider。常用語法如下：

```text
%wm_managed_world_count%
%wm_world_display_name:creative_world%
%wm_current_world_id%

<wm_managed_world_count>
<wm_world_display_name:creative_world>
<wm_current_world_id>
```

MiniPlaceholders使用`<wm_key>`，不是`<miniplaceholders:...>`。完整global、world與current-world key清單、空值規則及執行緒契約見[設定與 metadata](docs/configuration.md)。兩套provider只讀immutable snapshot；PlaceholderAPI輸出純文字，MiniPlaceholders則為Adventure Component。WorldManagement目前不在`messages_<locale>.yml`內執行任意第三方expansion。

## 啟動診斷

WorldManagement 在 `INFO` 層級輸出精簡的啟動階段摘要：設定與 storage、metadata 數量、啟用模組與可選 hook capability，以及總啟動耗時。例如：

```text
[WorldManagement] Enabling WorldManagement 1.0.0...
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
[WorldManagement]         Multiverse-Core: available
[WorldManagement]         PlaceholderAPI: available
[WorldManagement]         MiniPlaceholders: available
[WorldManagement]     Services
[WorldManagement]         Command service: /wm Brigadier tree initialized
[WorldManagement]         Suggestion service: metadata and online-player snapshots initialized
[WorldManagement]         Audit service: BEST_EFFORT policy, JSONL backend
[WorldManagement]         Online player snapshot listener registered
[WorldManagement]         Protection listener registered
[WorldManagement]         Lifecycle isolation listener registered
[WorldManagement]     Services ready: commands, suggestions, audit, and listeners initialized. Took 6ms
[WorldManagement] Enabled WorldManagement 1.0.0 with YAML metadata storage. Took 55ms
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
- metadata-first desired state、啟動/外部載入 bounded reconciliation、顯式存檔後卸載、unknown loaded runtime unload/delete、已載入世界的兩階段 delete confirm、nonblocking delete delay、transaction-bound `DELETING` tombstone、atomic quarantine/restore、同runtime permanent delete、shutdown/crash recovery 與 external reload abort
- YAML、SQLite、MySQL/MariaDB metadata provider，且永遠只有一個有效 provider；SQL 使用 HikariCP
- YAML atomic write、備份、毀損隔離；JSONL rotation 或 SQL audit store
- SQL metadata mutation 與其成功 audit event 使用同一 JDBC transaction；YAML provider 保持 metadata 原子檔案寫入後的非阻塞 JSONL audit
- `/wm` 採單一 immutable command specification，經 Paper Brigadier + `PluginBootstrap` 編譯實際 tree；同一 spec 驅動 Help、usage/error、權限/模組可見性、completion、canonical routes與runtime leaf IDs
- `/wm help [page|command path]` 支援 1-based 分頁與完整巢狀 path；錯誤子指令、缺少或多餘參數會回覆最近可見 usage 與 Help 提示
- `commands.yml` 可設定 root/module aliases與玩家是否預設免 Help 權限，Help topic與completion會依 sender permission及module enablement過濾
- completion只讀不可變metadata、loaded-world、線上玩家與每秒更新的管理權限snapshot；delete/unload/fallback可見唯一loaded runtime world，world owner只看到自己可管理的治理世界，最終授權仍由指令執行階段處理
- `modules.yml` 提供 lifecycle、warp、ownership、protection、storage 的獨立功能開關；停用模組時其 command branch 與治理listener不會啟用。identity conflict與`DELETING` runtime的lifecycle isolation listener永遠啟用
- `OFF`、`BEST_EFFORT`、`STRICT` audit policy；破壞性操作在 strict audit 無法排入時會拒絕
- bounded單一I/O worker、migration期間metadata mutation freeze/target rollback，以及不阻塞Paper thread的event-driven shutdown；未提交的teleport會立即拒絕，已提交的`teleportAsync`會與指令結果分離並持續drain到底層Paper future完成；同步shutdown admission失敗不會跳過terminal resource close。terminal resource close由有硬上限的受管daemon worker逐項隔離，既有I/O或close忽略interrupt時仍會嘗試後續資源，逾時future會明確失敗
- WorldManagement 專用 `OFF/BASIC/VERBOSE` 診斷、area allowlist、Paper console 與 bounded rotating file sink
- 可選 LuckPerms Warp外部權限整合；以已載入且identity相符的目的Bukkit world建立cached permission context，玩家名稱仍只由線上快照解析，WorldManagement rank不映射為LuckPerms group
- 可選 Multiverse-Core 5.2.0以上lifecycle整合；`remove`與第二次confirmed `delete`在改變WorldManagement狀態前使用MV公開API解除追蹤、保留Bukkit runtime並驗證`worlds.yml`持久化，不相容API、remove或save失敗時fail closed且釋放該world operation
- 可選PlaceholderAPI與MiniPlaceholders provider；共用`wm` namespace與immutable snapshot resolver，提供global、指定世界及玩家目前世界placeholder
- `messages_zh_TW.yml` 使用 Adventure MiniMessage，集中管理全部指令回覆、可選共用前綴、逐語意訊息 key，以及可由 `<term:...>` 重用的領域詞彙
- 玩家與 RCON 接收 Adventure Component；本機控制台以固定 ANSI 16 色呈現啟動摘要與指令回覆，Paper 檔案 log 保持純文字
- 自訂 locale 缺少的訊息或 `term.*` 詞彙會從 JAR 內建 template 自動補入並保存；無效 key 只在執行時回退，不覆寫管理員內容。詞彙固定以純文字 Component 插入，不能注入 MiniMessage 樣式或互動事件
- `hooks.yml` 預設資源，設定啟動時會驗證 hook

權限模型：`worldmanagement.bypass.protection`只用於保護與進入繞過，不提供ownership或Warp管理能力。全域管理分別使用`worldmanagement.admin.ownership.manage`與`worldmanagement.admin.warp.manage`，也可集中授予`worldmanagement.admin.*`。`owner set/remove`一律需要ownership管理員權限，世界owner不可自行轉讓或放棄。

MySQL/MariaDB 的 adapter 可使用 [設定與 metadata](docs/configuration.md) 中的 property-gated integration test，對專用測試資料庫做實際連線驗證。

目前交付範圍、驗證基線與尚待補強項目請參考 [實作狀態](docs/implementation-status.md)。

## 外部世界工具

WorldManagement 的治理功能只套用於明確登錄的ACTIVE世界。`/wm adopt <world>`只會為已載入世界建立本插件的metadata；不改變地圖檔、載入狀態或Multiverse等外部工具設定。啟用Multiverse-Core hook後，`/wm remove <world>`與第二次confirmed delete會在WorldManagement mutation前透過MV 5.2.0以上公開API解除追蹤並確認`worlds.yml`已保存，避免MV在之後reload或重啟時重新載入該世界；API不相容、linkage、remove或保存失敗時不會繼續detach、quarantine或刪除，也不會永久占用該world operation。唯一載入的unknown world可執行runtime-only unload，或在confirmed delete時先以exact identity建立DELETE_AUTO DETACHED metadata後進入安全刪除流程；除此之外，未登錄世界不套用保護、Warp或其他metadata操作。

## 文件

- [AI 協作規範](AGENTS.md)
- [架構](docs/architecture.md)
- [指令](docs/commands.md)
- [指令架構](docs/command-architecture.md)
- [設定與 metadata](docs/configuration.md)
- [實作狀態](docs/implementation-status.md)
