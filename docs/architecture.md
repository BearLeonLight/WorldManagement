# 架構

## 已實作邊界

WorldManagement 提供可持久化的受管世界 metadata、完整世界 lifecycle、快取式保護、Warp、audit 與可切換 storage provider。

### 執行緒模型

- `PluginIoExecutor` 是唯一執行 YAML 與其他阻塞 I/O 的 bounded單一背景worker；queue容量為1024，滿載或shutdown後的submission以exceptional future拒絕，不會阻塞或改在caller thread執行。一般 command、listener、scheduler callback 與 `onDisable()` 不等待 I/O。
- disable先停止lifecycle與teleport admission、取消plugin-owned scheduler task，讓尚未提交Paper API的pending teleport以false完成。已提交的`teleportAsync`則讓指令結果可立即以false完成，但其獨立drain future必須等待底層Paper future真正完成；不能把wrapper result當成runtime operation已結束。各元件若在`beginShutdown()`同步失敗或回傳null，會轉成exceptional future，仍由shutdown coordinator繼續terminal close。其後主I/O worker排空已接受的mutation/補償，並以最後一個受追蹤task協調repository、audit、pending storage與診斷資源關閉。若既有I/O忽略interrupt，`PluginIoExecutor`會原子提升尚未開始的terminal coordinator至受管daemon thread；各close仍由其擁有、命名且有硬上限的terminal I/O worker依序執行，共用shutdown deadline。單一close失敗或忽略interrupt不會跳過後續資源，錯誤以suppressed聚合，逾時future明確失敗且只可能留下有限的受管daemon terminal threads。Paper thread不等待，亦不建立未受管drain thread。
- `DiagnosticFileWriter` 是獨立的 best-effort bounded log writer；它不承載 metadata、audit 或其他業務 I/O，也不能回壓 Paper game thread。
- `WorldThreadDispatcher` 將遊戲互動送到 Paper 的 global、location 或 entity scheduler。
- 命令、事件與 Paper scheduler callback 不得等待 I/O future。
- `WorldRegistry` 保存不可變 `WorldMetadata` 快照；讀取操作不得讀 YAML 或 SQL。

### 功能模組

- `module/` 提供 `WorldManagementModule`、`ModuleConfiguration` 與 `ModuleManager`，集中管理可啟用的功能範圍。
- `modules.yml` 可獨立啟用或停用 `lifecycle`、`warp`、`ownership`、`protection`、`storage`。停用後對應 Brigadier command branch 不會提供；`protection`只控制entry/build/interact/container治理listener。world identity conflict與`DELETING` runtime的lifecycle isolation listener永遠啟用，不受Protection開關或一般protection bypass影響。
- Warp command implementation 位於 `warp/WarpCommandModule`；owner、rank 與 access implementation 位於 `ownership/OwnershipCommandModule`。主 command router 僅進行分派。

### 持久化

- `WorldMetadataRepository` 是 application 層唯一依賴的持久化介面。
- `config.yml` 的 `storage.provider` 選擇唯一有效 provider：`YAML`、`SQLITE`、`MYSQL` 或 `MARIADB`。SQL provider 必須提供 JDBC URL；不會 fallback 或雙寫。
- 設定檔與 metadata 都由 `PluginIoExecutor` 載入；`/wm` 在 lifecycle event 時註冊，但服務尚未完成時只會回覆 loading 狀態，metadata 載入失敗則 plugin fail-closed 停用。
- 啟動診斷在 `INFO` 層級輸出 `Enabling` 父行、設定/storage 與 metadata 載入階段、`Storage`、`Modules`、`Metadata`、`Hooks`、`Services` 分類明細，以及 `Enabled` 完成行與總耗時。每個 module 與已註冊服務各自輸出一行；可選整合在 `Hooks` 中獨立列出。metadata 詳細資料僅讀取 `WorldRegistry` immutable snapshot，列出世界名稱與 rank/access/player/warp 數量；不輸出 JDBC URL、帳密、玩家名稱或 UUID。計時只讀單調時鐘，不新增 I/O、排程或 Paper world API 存取。
- 可設定 debug pipeline 在 `config.yml` 載入後建立，與上述固定啟動摘要分離。它不修改 Paper 全域 logging level；console sink 使用 plugin `ComponentLogger`，file sink 固定寫入 data folder 的 `logs/debug.log`。
- `DiagnosticLogger` 在建立 event 或求值 supplier 前先檢查 `OFF/BASIC/VERBOSE` 與單一 area allowlist。event 只攜帶不可變字串、UUID、整數座標與數量，不能跨執行緒保留 Bukkit/Paper world、location、player 或 entity。
- file producer 只做 bounded queue `offer`；滿載時捨棄 best-effort event並彙總 dropped count。單 writer thread負責 UTF-8 escape、append、rotation及 retention。writer在既有 I/O bounded drain之後最後關閉，Paper disable callback不等待。
- `PROTECTION` 不逐事件 enqueue；listener只更新 lock-free counter，30 秒窗口到期後由後續事件觸發摘要。completion、snapshot/registry lookup、access-policy read、正常 scheduler dispatch與每次 I/O submit也禁止逐次診斷。
- `YamlWorldMetadataRepository` 使用每世界一份 `plugins/WorldManagement/worlds/<world>.yml`，overwrite/delete 前建立 backup；schema migration 會先 preflight 全部檔案並建立完整 provider snapshot，future schema 不會造成部分 rewrite。單一毀損檔會隔離至 quarantine，避免阻斷其餘 aggregate。
- `JdbcWorldMetadataRepository` 將完整 schema payload 寫入單一資料表，以 SQL `WHERE version = ?` 實作 optimistic locking；它初始化 schema version 與 audit table。SQLite、MySQL 與 MariaDB 共用此 adapter。schema upgrade 先建立備份、完成冪等 DDL，最後才更新 version marker；MySQL/MariaDB 使用 advisory lock 序列化 migration。
- SQL provider 的 `create`、`replace`、`delete` metadata mutation 可透過 storage 的 audited contract 與成功 audit event 使用同一 JDBC connection/transaction；任何 audit insert 失敗都會 rollback metadata，快取只在 commit 後更新。
- 發行 JAR 以 Shadow 封裝並 relocate BoostedYAML 與 SnakeYAML，避免與其他 plugin 的 runtime classpath 衝突。
- 寫入先建立同目錄暫存檔，再以原子移動取代目標；不支援原子移動的檔案系統退回一般 replace。
- aggregate 使用 `version` 進行 optimistic version 檢查。快取只會在 repository 成功寫入後更新；關機時先拒絕新 I/O，再由I/O worker排空queue並執行terminal close，不阻塞Paper game thread。
- `StorageMigrator` 透過 repository contract 驗證、複製及重新讀取驗證 aggregate；migration與一般metadata mutation共用`MetadataMutationGate`。成功後mutation保持frozen，直到管理員切換provider並重啟；失敗會盡力清除partial target並重新開放active provider mutation。`/wm storage migrate <source> <target> confirm`只支援明確設定的target，保留來源且不隱式切換active provider。

完整架構計畫與尚待補強項目請參考 [實作狀態](implementation-status.md)。

### Adopt

`adopt` 只為已被 Paper 載入且尚未登錄的世界建立 WorldManagement metadata。它不建立、載入、卸載、重新命名、複製或修改世界資料，也不修改其他世界管理工具的設定。使用`--detached`時第一次durable create直接寫入DETACHED，不經過ACTIVE中間狀態或第二次remove mutation。

新 metadata 預設為：

- owner：`server`
- rank system：啟用
- access control：`NONE`
- ranks：`OWNER` 與 `GUEST`
- players：空
- warps：空

### World Lifecycle

- lifecycle runtime operation source 位於 `world/lifecycle/`：`WorldLifecycleCoordinator` 協調流程，`WorldRuntimeGateway` 與 `PaperWorldRuntimeGateway` 隔離 Paper 呼叫，`WorldDirectoryRemover` 處理 I/O 刪除，`WorldOperationState` 管理每世界互斥狀態。
- `create`、`load`、`unload`、`remove`、`import` 與 `delete` 的 Paper world API 操作都在 global scheduler 執行。
- `create`以typed options接收seed、generator、generator settings、structures、bonus chest、biome provider、forced spawn及detached狀態，再映射至Paper `WorldCreator`。generator與biome provider reference保存於metadata，create及後續managed load都只透過已啟用plugin的公開API解析；null、例外或停用provider一律fail closed。seed、settings、structures、bonus chest與forced spawn只在建立時交給Paper或由世界資料保存。bonus chest與forced spawn互斥，避免接受Paper無法同時履行的建立要求。MV的alias、game mode、difficulty、auto-load、world price與portal設定不是Paper世界生成資料，刻意不由create模擬。
- `create` 或 `import` 若在Paper已產生runtime副作用後回傳`null`或丟例外，coordinator會在global scheduler卸載partial runtime。`create`另以原始creation claim在I/O worker清除本次建立的partial storage；`import`保留既有世界目錄。
- `import` 只接受 world container 的真實直接子目錄、拒絕符號連結、Windows junction/reparse point與其他特殊filesystem entry，且必須包含 `level.dat`。live world、quarantine root與quarantine entry都以`NOFOLLOW_LINKS` attributes及real-path direct-parent confinement驗證。
- ACTIVE/DETACHED `load` 與 `unload` 先在global scheduler完成runtime收斂與identity驗證，再持久化`LOADED`/`UNLOADED` desired state；失敗時保留原intent。`load <world> <environment> --detached`重用安全import claim，為unknown unloaded storage直接建立DETACHED metadata。unknown loaded unload則只save/relocate/unload runtime，不建立metadata。Paper沒有world save completion future，因此save/close仍是global tick上的同步Paper操作，不會移至I/O worker。啟動時及外部 `WorldLoadEvent` 下一 tick 會進行最多三次 bounded reconciliation；僅ACTIVE世界會自動reconcile。
- `remove` 接受任何ACTIVE world，不論loaded/unloaded狀態；Multiverse-Core hook啟用時先解除MV追蹤並驗證其設定保存，再將metadata改為`DETACHED`。loaded世界保持runtime不變、玩家不移動。DETACHED世界不受Warp/Ownership/Protection治理，也不執行自動reconciliation或identity auto-mutation。`manage`可重新啟用，採用目前runtime狀態作為新desired state；identity不符則拒絕。`remove <world> purge confirm`才永久清除 detached metadata。
- `delete` 支援ACTIVE、DETACHED及唯一unknown loaded world。unknown target只能從`LoadedWorldCatalog`的exact runtime identity自動建立`DELETE_AUTO` DETACHED metadata；identity collision/replacement或adoption失敗不會觸碰storage。有玩家時command/configured/primary fallback都可為任一不同且唯一loaded runtime world，target identity在teleport後重驗。第一次confirmed delete只save、unload並保存`UNLOADED`；第二次先解除MV追蹤，再建立transaction-bound quarantine claim並以identity/version/transaction CAS寫入`DELETING`，同一runtime接著刪除quarantine storage並purge metadata。
- 同一世界的 create/load/unload/remove/import/delete 由 keyed `WorldOperationState` gate 互斥；進行中的第二個操作會立即拒絕。
- `manage`與`purge`也進入同一 keyed gate；Warp/Ownership治理mutation在I/O worker套用前重新確認最新state仍為ACTIVE。display-name使用ACTIVE-or-DETACHED lifecycle mutation並二次檢查最新state。
- unload/delete 只有在目標仍有玩家時才要求不同且已載入的 fallback；delete 另要求明確 `confirm`。
- fallback teleport 全數成功、target仍符合pinned identity且原世界已無玩家後，才回到 global scheduler 卸載；任何排程、teleport或target replacement失敗都會保留source world。
- 第二次confirmed delete由 global scheduler 執行 nonblocking deletion delay；tombstone前的reload、metadata failure或shutdown cancellation會restore quarantine。durable `DELETING`後，同一runtime在I/O worker永久刪除quarantine storage並purge metadata；若shutdown已阻止後續global continuation，則回報`PENDING_RESTART`並由下次啟動核對transaction claim後接續。若tombstone寫入期間外部插件重載world，該runtime會被隔離並回報需人工檢查；claim與live world path並存、identity/version/transaction不符或ambiguous claim一律fail closed。
- `/wm tp` 從 immutable lifecycle metadata 與線上玩家 snapshot解析verified target；ACTIVE套用access policy，DETACHED跳過WorldManagement governance，實際傳送仍由player entity scheduler執行。

### Protection And Warps

- 保護 listener 僅讀 `WorldRegistry` immutable snapshot，攔截 entry、break/place、interact 與 container open，不執行 I/O。
- rank/access policy 支援 bypass、server owner 快速放行、白名單/黑名單及 rank permissions。
- Warp 是 metadata aggregate 的 schema 2 欄位。私有 Warp 同時檢查 `USE_PRIVATE_WARP`、trust/owner/rank 與可選 external permission。
- 實際 Warp teleport 透過 player entity scheduler 與 `teleportAsync` 執行。
- LuckPerms以optional dependency載入。hooks.yml啟用且API存在時，`DestinationWorldPermissionResolver`先要求`LoadedWorldCatalog`中有唯一、identity相符的目的world，再由`LuckPermsCachedPermissionLookup`複製玩家目前的contextual `QueryOptions`、只替換`world`context並查cached permission。API/user/context缺失或查詢異常時fail closed；不執行I/O、load或離線lookup。一般command permission與protection bypass仍由Paper/Bukkit處理，玩家名稱只由線上快照解析，WorldManagement rank永不映射為LuckPerms group。
- Multiverse-Core 5同樣是optional dependency，但啟用lifecycle hook最低需要5.2.0；該版才提供保留Bukkit runtime的`RemoveWorldOptions`。連線時先探測此API，不相容版本標示API unavailable並讓remove/delete fail closed。API可用時，`WorldTrackingHook`在global scheduler呼叫`WorldManager.removeWorld(RemoveWorldOptions...unloadBukkitWorld(false))`，只解除MV追蹤而保留Bukkit runtime；之後再明確檢查`saveWorldsConfig()`，因MV remove流程本身不會將內部save failure暴露為remove failure。保存失敗會保留retryable pending persistence並阻止WorldManagement lifecycle mutation。runtime linkage failure會轉為FAILED並釋放per-world operation，不得讓scheduler callback拋出Error後留下永久gate。MV API同步觸發Bukkit event與YAML保存，因此不得移到I/O worker；這是第三方API在Paper thread上的已知同步I/O限制。

### Placeholder Providers

- PlaceholderAPI與MiniPlaceholders是獨立optional provider，兩者都註冊短namespace `wm`，共用`WorldPlaceholderResolver`的global、指定世界與audience key catalog。PAPI公開`%wm_key%`及`%wm_world_key:world_id%`純文字；MiniPlaceholders公開`<wm_key>`及`<wm_world_key:world_id>` Adventure Component。
- resolver每次只讀`WorldManagementService.snapshot()`回傳的immutable `RegistrySnapshot`、`LoadedWorldCatalog.findExactUnique`及`PlayerWorldContextSnapshot`。後者只在join、world change、quit與啟動backfill時於合法Paper context擷取UUID、world ID、registry key與world UUID；第三方callback不保留或讀取mutable Bukkit/Paper物件。
- metadata-derived lookup cache以`RegistrySnapshot` reference identity失效，預先建立world ID map及驗證後顯示名稱Component；cache大小受metadata世界數限制。callback不做YAML、JDBC、filesystem I/O、future等待、scheduler dispatch或離線玩家lookup。
- current-world值只接受玩家snapshot、ACTIVE metadata、VERIFIED identity與唯一loaded runtime四者完全一致；stale/replacement/unloaded/detached context一律fail closed為false或空值。PAPI只輸出plain text，MiniPlaceholders只讓已由`DisplayNameValidator`驗證的世界顯示名稱保留Component格式。
- `PlaceholderHookManager`在metadata ready後註冊，監聽dependency enable/disable並在shutdown清理。provider註冊先檢查既有`wm` identifier；collision不覆寫，cleanup也只在registry仍指向同一instance時解除。
- locale message rendering刻意不作為任意第三方placeholder consumer。PAPI與MiniPlaceholders expansion callback均同步執行，開放無限制consumer會把外部I/O或鎖延遲帶入Paper delivery thread；PAPI字串若重新送入MiniMessage parser也會形成標籤注入面。

### Audit

- 成功的 adopt、metadata lifecycle、owner/rank/access 與 Warp set/delete/trust 操作會產生 audit event。SQL provider 將 event 與 metadata mutation 原子提交；YAML provider 在 metadata 原子檔案寫入後以非阻塞方式追加 `plugins/WorldManagement/audit.jsonl`。
- audit 支援 `OFF`、`BEST_EFFORT` 與 `STRICT` admission；delete、remove、detached metadata purge、owner transfer與storage migration只有在effective STRICT admission event已成功持久化後才開始。點號分層action會繼承最近的parent override，因此`world.remove.purge`可繼承`world.remove`。

## 後續邊界

SQL provider 的本地驗證使用 SQLite；MySQL/MariaDB 需在目標伺服器提供 JDBC URL 與帳密後進行整合測試。新增功能必須維持上述 I/O、快取和 repository 邊界。
