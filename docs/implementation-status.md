# 實作狀態

本文件是 [完整架構計畫](../plan/PWorldManagement_完整架構.md) 的實際交付狀態。計畫文件保留原始設計決策與驗收方向；本文件以目前 source、tests、resources 與 runtime smoke test 為準。

最後核對：2026-07-31。

## 已完成

- Java 25 Gradle Kotlin DSL、Shadow JAR、`paper-plugin.yml`、可選 LuckPerms compile-only dependency，以及支援 TestServer 自動偵測、Paper Fill v3 下載、SHA-256 驗證與 Gradle cache 的 `paperJarSmokeTest`。
- 核心 I/O/threading 邊界：bounded `PluginIoExecutor`、`WorldThreadDispatcher`、event-driven shutdown drain、由有硬上限的受管terminal I/O workers逐項隔離且聚合錯誤的resource close、可觀察的shutdown timeout、拒絕symlink/junction/reparse point的world name/real-path confinement、訊息與設定載入。
- YAML、SQLite、MySQL/MariaDB 的單一 metadata provider；config schema 1、metadata schema 3、legacy canonical rewrite、future schema fail-closed、YAML atomic write/backup/quarantine、Hikari JDBC 與 optimistic locking。
- 不可變 metadata aggregate 與 `WorldRegistry` 原子 snapshot 替換；成功 persistence/transaction commit 後才更新快取。
- `world/lifecycle/` 的create/load/unload/remove/manage/import/adopt/delete、DETACHED與DELETING metadata、metadata-first desired state、startup/WorldLoadEvent bounded reconciliation、startup configured fallback validation、optional command fallback、顯式save後卸載、metadata更新失敗的global reload補償、已載入世界兩階段delete confirm、nonblocking deletion delay、同一runtime永久刪除、故障時startup-finalized atomic quarantine、crash recovery、external reload/tombstone compensation、startup live-path conflict fail-closed、create/import null/throw compensation、coordinator-owned global affinity、per-world operation gate、entity-affine player teleport與I/O directory removal。
- `/wm tp self|player|--any` 支援受管 world spawn 或座標傳送；玩家名稱只由線上 snapshot 解析，access 只讀 immutable metadata，實際傳送在 entity scheduler。
- cache-only authorization/protection、owner/rank/access control、Warp/trust，以及以已載入且identity相符的目的Bukkit world context查詢cached permission的optional LuckPerms整合。
- Paper Brigadier `/wm` command tree；所有現有 branch 皆為 literal 或個別 argument node，不使用 greedy argument，因此候選只會替換目前參數。
- Adventure MiniMessage 指令輸出：逐語意 locale keys、完整 template 標籤、安全 literal placeholders、可選共用前綴、JAR 內建單鍵 fallback，以及 player/console/RCON/proxy sender scheduler routing。
- `OFF`、`BEST_EFFORT`、`STRICT` audit policy與點號分層override；破壞性command在STRICT admission持久化後才執行；SQL metadata mutation與成功audit event同一transaction；YAML使用非阻塞JSONL append/rotation。
- storage migration與metadata mutation共用gate；成功後凍結mutation等待provider切換，失敗時清除partial target並恢復active provider。
- `modules.yml`：lifecycle、warp、ownership、protection、storage 的 enablement；Warp 與 ownership command implementation 各自位於對應 feature package。
- `.github/agents/paper-performance-reviewer.agent.md`、`.github/skills/storage-migration/SKILL.md` 與 `.github/skills/command-verification/SKILL.md` 已存在，分別負責唯讀 concurrency review、安全 migration，以及依契約選擇 JUnit、Paper console runtime 或 Mineflayer player E2E；skills 不複製固定指令清單。
- `paperConsoleCommandTest`會在不安裝Mineflayer或Via的情況下，以隔離Paper console驗證29個console runtime leaves、42個console command outcomes、aliases、world storage、metadata、identity recovery、quarantine、migration target與terminal shutdown completion。`paperPlayerE2eTest`保留15個依賴真實玩家sender、線上名稱快照、位置、世界傳送、protection event、identity隔離或fallback relocation的leaves，並驗證27個player command outcomes；另由Paper server-side event、dimension、metadata與block predicate驗證break/place/interact/container final state、identity replacement `CONFLICT` persistence、bypass/direct entry denial及post-respawn fallback relocation，不只依賴Mineflayer cache或聊天文字。task先以status ping選擇Paper原生協定，只有不受支援或spawn前明確protocol相容性失敗才按需安裝Via fallback；另載入真實LuckPerms plugin，驗證來源world deny、目的world allow、公開API destination query與required-permission Warp實際成功。`e2e/command-runtime-coverage.txt`與JUnit tree traversal會拒絕未分類、重複或未知層級的executable path，兩個runner也會把實際command skeleton、arity、aliases與case ID綁定。共同Node harness tests會拒絕build本身、專案根目錄與build外的destructive test directory。

## 部分完成或待補強

- **Paper integration coverage**：已有JUnit focused tests、實際JAR startup/`wm list` smoke test、不需玩家的29-leaf console runtime matrix，以及15-leaf player runtime matrix；尚未有MockBukkit或等效測試覆蓋listener註冊與module enablement的所有組態排列。
- **玩家端 E2E 相容性**：選用task使用Mineflayer 4.37.1公開的`testedVersions`。原生版本若可用就不下載或安裝Via；Paper 26.2 protocol 776目前不受直接支援，因此已驗證task會先乾淨停止無Via的native attempt，之後才從Gradle cache取得SHA-256驗證的ViaVersion/ViaBackwards 5.11.0，並以Mineflayer當下最新版1.21.11完成15個玩家必要leaves、27個player command outcomes，以及獨立的server-side final-state assertions與fallback relocation。尚無本機Mineflayer支援版本的Paper JAR可執行原生完整runtime flow；零Via準備/安裝契約目前由17個注入式Node策略測試覆蓋。
- **多語系擴充**：目前所有 command 回應已使用 `messages_zh_TW.yml`；若新增其他 locale，需提供對應 JAR 內建 resource，才能維持逐鍵 fallback 契約。
- **Storage migration audit**：migration 會做 aggregate copy/reload verification，且 command 有 strict admission；仍未驗證/搬移 audit event counts 或抽樣 audit content，成功 migration event 也不是 target provider transaction 的一部分。
- **Provider schema backup**：YAML legacy rewrite 前會 preflight 全部檔案並建立完整 provider snapshot；SQLite schema upgrade 已由測試驗證會先建立 native database snapshot。MySQL/MariaDB 的 repeatable-read metadata/audit JSONL、row counts 與 SHA-256 manifest 已有 focused test，但 advisory lock 與真實 transaction isolation 仍需專用外部資料庫做 integration verification。
- **LuckPerms**：已以真實LuckPerms-Bukkit 5.5.53 runtime驗證destination-world cached permission與required-permission Warp。artifact由本機路徑、相鄰workspace或固定Modrinth下載取得，下載路徑驗證SHA-512。仍未涵蓋LuckPerms熱載入/卸載或non-contextual query mode；這些情況按設計fail closed。玩家名稱解析刻意只使用線上快照，不注入離線identity lookup。
- **Shutdown coverage**：shutdown coordinator會停止lifecycle與teleport admission、取消pending global task、立即終止尚未提交Paper API的teleport，並等待已提交的`teleportAsync`底層future真正完成，再由主I/O worker排空已接受operation/restore補償；任一元件的`beginShutdown()`同步失敗或null future會轉成exceptional future，不會阻止terminal close啟動。若既有I/O忽略interrupt，`PluginIoExecutor`會將尚未開始的terminal coordinator原子提升至受管daemon thread，最後以有硬上限的terminal I/O workers依序執行repository/audit/pending-storage/diagnostic close。Paper game thread不等待I/O。正常排空、queue滿載terminal保留、lifecycle timeout、I/O timeout下的interruptible與忽略interrupt既有I/O、close failure聚合、diagnostic close、interruptible與忽略interrupt的blocked close隔離、quarantine後取消、提交前teleport取消、已提交Paper teleport drain、scheduler同步例外與shutdown admission同步例外已有deterministic test；SQLite/MySQL/MariaDB provider-specific pool close仍需dedicated integration test。
- **Paper world save限制**：unload/delete會在global scheduler顯式呼叫同步`World.save(true)`，成功後才以`save=false`卸載；已載入世界的第一次confirmed delete只完成此步驟，第二次才在I/O worker永久刪除storage。Paper目前沒有公開的async world-save completion API，因此大型世界的save/close仍可能增加該tick延遲；將Bukkit world API移至I/O worker不是合法替代方案。
- **外部資料庫**：SQLite contract tests 已執行；MySQL/MariaDB integration test 為 property-gated，需提供專用資料庫後在 CI 或目標環境執行。
- **動態補全**：Brigadier tree解決正確replacement range；舊`AsyncTabCompleteEvent` completer未註冊。現有suggestion provider只讀immutable metadata與`OnlinePlayerSnapshot`；Warp候選不讀sender，只列identity已驗證、無外部permission且能由metadata證明對任何玩家皆可使用的PUBLIC Warp。依賴owner、bypass、玩家rank、private trust或`required-permission`的Warp，以及需要owner/bypass的管理型world argument，刻意不提供候選，最終授權仍在執行階段。若日後需要顯示玩家特定候選，必須先建立不可變permission/authorization snapshot。
- **Regionized runtime與高頻保護成本**：一般Paper runtime已驗證；WorldLoad identity隔離目前從global callback讀取`World#getPlayers()`後，再把每位玩家工作派到entity scheduler，尚無Folia/regionized runtime驗證。break/place/interact/container每次會capture完整Paper world identity，沒有I/O但仍有可避免的getter與allocation成本；若要支援regionized runtime或大規模壓測，應建立由world/player事件維護的不可變runtime identity/player-world snapshot。

## 刻意不實作

下列項目不在目前範圍：跨伺服器同步、Web 管理台、世界 clone/backup、WorldEdit/Multiverse 設定雙向同步、未載入世界的 adopt 模式。

## 驗證基線

```powershell
.\gradlew.bat check
.\gradlew.bat paperJarSmokeTest
.\gradlew.bat paperConsoleCommandTest
# 僅在需要玩家端黑箱驗證時執行
.\gradlew.bat paperPlayerE2eTest
```

`check`是每次程式變更的必要驗證。第二個命令驗證shaded plugin能在隔離伺服器載入metadata、以console執行`wm list`並正常停機；第三個命令執行29-leaf console command matrix，且不安裝Mineflayer或Via。第四個命令才會安裝Mineflayer dependencies，先執行原生/Via決策與artifact hash策略測試，再執行15-leaf玩家端登入、指令、互動、identity隔離、LuckPerms destination-context及登出流程；只有實際需要fallback時才取得Via artifacts。三個Paper task都先使用相鄰`TestServer/`的JAR，若不存在則透過官方Fill v3 API下載匹配版本、驗證SHA-256並快取。`check`刻意不依賴Node或這些外部程序整合測試。
