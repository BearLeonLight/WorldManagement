# 實作狀態

本文件是 [完整架構計畫](../plan/PWorldManagement_完整架構.md) 的實際交付狀態。計畫文件保留原始設計決策與驗收方向；本文件以目前 source、tests、resources 與 runtime smoke test 為準。

最後核對：2026-08-03。

## 已完成

- Java 25 Gradle Kotlin DSL、Shadow JAR、`paper-plugin.yml`、可選 LuckPerms compile-only dependency，以及支援 TestServer 自動偵測、Paper Fill v3 下載、SHA-256 驗證與 Gradle cache 的 `paperJarSmokeTest`。
- 核心 I/O/threading 邊界：bounded `PluginIoExecutor`、`WorldThreadDispatcher`、event-driven shutdown drain、由有硬上限的受管terminal I/O workers逐項隔離且聚合錯誤的resource close、可觀察的shutdown timeout、拒絕symlink/junction/reparse point的world name/real-path confinement、訊息與設定載入。
- YAML、SQLite、MySQL/MariaDB 的單一 metadata provider；config schema 1、metadata payload schema 4（schema 1至3向後相容）、legacy canonical rewrite、future schema fail-closed、YAML atomic write/backup/quarantine、Hikari JDBC 與 optimistic locking。generator、deletion transaction與registration source provenance由共用payload codec持久化，不需變更JDBC table schema。
- 不可變 metadata aggregate 與 `WorldRegistry` 原子 snapshot 替換；成功 persistence/transaction commit 後才更新快取。
- `world/lifecycle/` 的create/load/unload/remove/manage/import/adopt/delete、unknown loaded runtime unload/delete auto-adopt、`load <world> <environment> --detached`、DETACHED/DELETING/DELETE_AUTO metadata、any-loaded fallback、metadata-first desired state、bounded reconciliation、顯式save後卸載、兩階段delete confirm、transaction-bound tombstone、restart-finalized quarantine recovery、create/import compensation、global affinity、per-world operation gate與entity-affine player teleport。
- `/wm tp self|player|--any` 支援ACTIVE與DETACHED world spawn或座標傳送；ACTIVE套用immutable治理policy，DETACHED只要求verified lifecycle identity與command permission。display-name同樣支援ACTIVE/DETACHED，Warp/Ownership/Protection維持ACTIVE-only。
- cache-only authorization/protection、owner/rank/access control、Warp/trust，以及以已載入且identity相符的目的Bukkit world context查詢cached permission的optional LuckPerms整合。
- Paper Brigadier `/wm` command tree由單一immutable specification編譯；同一spec驅動Help、usage/error、permission/module visibility、completion、canonical routes與runtime leaves。一般branch使用literal或個別argument node；Help query與display name使用terminal greedy argument，create flags使用terminal typed argument，domain handler不解析raw command text。
- Adventure MiniMessage 指令輸出：逐語意 locale keys、完整 template 標籤、安全 literal placeholders、可選共用前綴、JAR 內建單鍵 fallback，以及 player/console/RCON/proxy sender scheduler routing。
- `OFF`、`BEST_EFFORT`、`STRICT` audit policy與點號分層override；破壞性command在STRICT admission持久化後才執行；SQL metadata mutation與成功audit event同一transaction；YAML使用非阻塞JSONL append/rotation。
- storage migration與metadata mutation共用gate；成功後凍結mutation等待provider切換，失敗時清除partial target並恢復active provider。
- `modules.yml`：lifecycle、warp、ownership、protection、storage 的 enablement；Warp 與 ownership command implementation 各自位於對應 feature package。
- `.github/agents/paper-performance-reviewer.agent.md`、`.github/skills/storage-migration/SKILL.md` 與 `.github/skills/command-verification/SKILL.md` 已存在，分別負責唯讀 concurrency review、安全 migration，以及依契約選擇 JUnit、Paper console runtime 或 Mineflayer player E2E；skills 不複製固定指令清單。
- `paperConsoleCommandTest`與`paperPlayerE2eTest`分別負責console與真實玩家runtime契約；runtime manifest目前分類32個console leaves與15個player leaves，包含新增的load detached leaf。JUnit tree traversal會拒絕未分類、重複或未知層級的executable path，runner以server-side metadata、filesystem、event與dimension final state驗證結果。

## 部分完成或待補強

- **Paper integration coverage**：已有JUnit focused tests、實際JAR startup/`wm list` smoke test、不需玩家的32-leaf console runtime matrix，以及15-leaf player runtime matrix；尚未有MockBukkit或等效測試覆蓋listener註冊與module enablement的所有組態排列。
- **Command block scheduler routing**：`CommandMessageSender`目前將非player sender派送到global scheduler，尚未依`BlockCommandSender`的方塊位置使用`executeAt`，command minecart也尚未做entity-affine routing。一般Paper console/RCON/player/proxy路徑已有測試；若要支援regionized command block或minecart執行，需擴充sender target與聚焦測試。
- **玩家端 E2E 相容性**：選用task使用Mineflayer 4.37.1公開的`testedVersions`。Paper 26.2 protocol 776目前不受直接支援，因此task從Gradle cache取得SHA-256驗證的ViaVersion/ViaBackwards 5.11.0，以`-Xms512M -Xmx1024M`執行多世界、Via與LuckPerms矩陣，並以Mineflayer 1.21.11完成15個player leaves、33個player command outcomes，以及loaded remove、DETACHED治理/tp、unknown fallback/delete、identity與protection的server-side assertions。identity replacement重啟階段停用Protection模組，仍驗證login、一般bypass entry與post-respawn lifecycle isolation。原生/Via、artifact與有界output monitor策略由21個Node測試覆蓋。
- **多語系擴充**：目前所有 command 回應已使用 `messages_zh_TW.yml`；若新增其他 locale，需提供對應 JAR 內建 resource，才能維持逐鍵 fallback 契約。
- **Storage migration audit**：migration 會做 aggregate copy/reload verification，且 command 有 strict admission；仍未驗證/搬移 audit event counts 或抽樣 audit content，成功 migration event 也不是 target provider transaction 的一部分。
- **Provider schema backup**：YAML legacy rewrite 前會 preflight 全部檔案並建立完整 provider snapshot；SQLite schema upgrade 已由測試驗證會先建立 native database snapshot。MySQL/MariaDB 的 repeatable-read metadata/audit JSONL、row counts 與 SHA-256 manifest 已有 focused test，但 advisory lock 與真實 transaction isolation 仍需專用外部資料庫做 integration verification。
- **LuckPerms**：已以真實LuckPerms-Bukkit 5.5.53 runtime驗證destination-world cached permission與required-permission Warp。artifact由本機路徑、相鄰workspace或固定Modrinth下載取得，下載路徑驗證SHA-512。仍未涵蓋LuckPerms熱載入/卸載或non-contextual query mode；這些情況按設計fail closed。玩家名稱解析刻意只使用線上快照，不注入離線identity lookup。
- **Shutdown coverage**：shutdown coordinator會停止lifecycle與teleport admission、取消pending global task、立即終止尚未提交Paper API的teleport，並等待已提交的`teleportAsync`底層future真正完成，再由主I/O worker排空已接受operation/restore補償；任一元件的`beginShutdown()`同步失敗或null future會轉成exceptional future，不會阻止terminal close啟動。若既有I/O忽略interrupt，`PluginIoExecutor`會將尚未開始的terminal coordinator原子提升至受管daemon thread，最後以有硬上限的terminal I/O workers依序執行repository/audit/pending-storage/diagnostic close。Paper game thread不等待I/O。正常排空、queue滿載terminal保留、lifecycle timeout、I/O timeout下的interruptible與忽略interrupt既有I/O、close failure聚合、diagnostic close、interruptible與忽略interrupt的blocked close隔離、quarantine後取消、提交前teleport取消、已提交Paper teleport drain、scheduler同步例外與shutdown admission同步例外已有deterministic test；SQLite/MySQL/MariaDB provider-specific pool close仍需dedicated integration test。
- **Paper world save限制**：unload/delete會在global scheduler顯式呼叫同步`World.save(true)`，成功後才以`save=false`卸載；第一次confirmed delete只完成此步驟，第二次在I/O worker quarantine並寫入tombstone，永久刪除由下次啟動I/O recovery完成。Paper目前沒有公開的async world-save completion API，因此大型世界的save/close仍可能增加該tick延遲；將Bukkit world API移至I/O worker不是合法替代方案。
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

`check`是每次程式變更的必要驗證。第二個命令驗證shaded plugin啟動與正常停機；第三個命令執行32-leaf console command matrix；第四個命令執行15-leaf玩家端登入、指令、互動、identity隔離、LuckPerms destination-context及fallback relocation。`check`刻意不依賴Node或外部Paper程序整合測試。

環境重置時，`./gradlew.bat clean`只清除`build/`；`./gradlew.bat cleanE2eDependencies`可選擇清除console與player E2E的Node dependencies，`./gradlew.bat cleanWorldManagementE2eCache`可選擇清除Gradle user home內僅屬於WorldManagement的Paper、Via與LuckPerms下載快取。兩個深度清理task均不會由標準`clean`隱式執行。
