# 指令

## `/wm help [page|command path]`

`/wm help` 顯示第 1 頁，每頁最多 6 個目前 sender 可見的頂層 topic；`/wm help <page>` 使用 1-based 頁碼；`/wm help <完整指令路徑>` 可查詢 group 或 leaf，例如 `/wm help ownership rank set`。command path 大小寫不敏感，Help completion 直接走訪實際 command specification。

一般 Help 只顯示 sender 具有 command permission 且 module 已啟用的項目。`worldmanagement.command.help.all` 可查看所有已啟用 module 的指令說明，但不會略過 module enablement；不可見 topic 與不存在 topic 使用相同回覆，避免洩漏權限資訊。`worldmanagement.command.help` 與 `.help.all` 預設 OP，console/RCON 始終可使用 Help；玩家免權限預設值由 `commands.help.players-enabled` 控制。

已知指令缺少參數、固定 literal 錯誤或 terminal 後出現多餘參數時，Brigadier 會依同一 specification 回覆最近可見的 canonical usage 與 `/wm help <topic>` 提示。這些錯誤不會落到全域 unknown-command handler，也不解析 raw command text。

## `/wm adopt <world> [--detached]`

權限：`worldmanagement.command.adopt`，預設 OP。

將一個已載入、尚未被 WorldManagement 登錄的世界加入管理。世界名稱只能包含英文字母、數字、底線和減號。加入 `--detached` 時第一次 durable metadata mutation 直接建立為 `DETACHED`，不會先短暫進入 `ACTIVE`。

此指令只建立 WorldManagement metadata，不會改變世界檔案、Paper 載入狀態、玩家位置或 Multiverse 等外部工具設定。`DETACHED`仍可使用 lifecycle load/unload/delete，但不套用 Warp、Ownership、Protection、自動 reconciliation 或 identity auto-mutation。

## `/wm list [detached|all]`

權限：`worldmanagement.command.list`，預設 OP。

無參數時列出 WorldManagement 記憶體快取中的ACTIVE世界；加入`detached`可列出已停止管理、但仍保留metadata的世界。這兩種清單使用`<world-id或顯示名稱> - <NORMAL|NETHER|THE_END|CUSTOM>`格式逐行顯示；設定顯示名稱後以該名稱為主，游標停留時顯示不可變的world ID。

加入`all`時列出事件維護的不可變snapshot中所有唯一loaded runtime worlds，格式為`<world-id> - <environment> - <ACTIVE|DETACHED|UNKNOWN>`。只有runtime identity與metadata完全相符時才標示ACTIVE或DETACHED；replacement、identity衝突及無metadata世界一律標示UNKNOWN。三種模式都不讀取YAML、SQL或可變Bukkit world。

## 訊息輸出

所有 `/wm`、root alias 與 module alias 回覆都使用 Adventure Component，內容由 `messages_<locale>.yml` 的 MiniMessage template 控制。控制台與 RCON 由 Paper 正常呈現或降級；代理指令依最終 caller audience 選擇正確的 player entity scheduler 或 global scheduler。

管理員可在 template 使用完整 MiniMessage 標籤。命令參數、metadata 名稱、玩家名稱、UUID 與清單等動態值固定以純文字 placeholder 插入，不能注入顏色、click 或 hover 事件。自訂 locale 的單一 key 缺失或無效時，只回退該 key 到 JAR 內建繁中 template。

Domain rejection 使用 typed status 映射至固定 locale key；例如 not managed、service loading、operation in progress、identity mismatch、storage missing 與 fallback unavailable 都有獨立原因。非預期 backend exception 只回覆固定的內部錯誤與檢查紀錄提示，不會把 exception message、stack trace 或被拒絕的原始 option token回顯給 sender。

## World Lifecycle

- `/wm create <world> <NORMAL|NETHER|THE_END> <NORMAL|FLAT|AMPLIFIED|LARGE_BIOMES> [--seed <seed>] [--generator <plugin[:id]>] [--generator-settings <json>] [--no-structures] [--generate-bonus-chest] [--biome <plugin[:id]>] [--force-spawn-position <x,y,z[,yaw,pitch]>] [--detached]`：environment與world type為必填。flags可任意排序、各只能出現一次；completion逐token只提供尚未使用的flag，選取value option後在下一個token提供對應value，不會把多個options合併成單一suggestion。seed只接受ASCII英數，數字直接作為`long`，文字使用Minecraft相容的Java字串hash。generator settings可用引號保留含空白JSON；`--no-structures`停用結構、`--generate-bonus-chest`產生獎勵箱、`--biome`解析plugin biome provider，forced spawn可提供座標及選用yaw/pitch。bonus chest與forced spawn不可並用。generator與biome completion只讀主執行緒發布的有效plugin不可變snapshot，仍可手動輸入`plugin:id`；provider無效、停用、回傳null或拋例外時fail closed。generator與biome provider reference都會寫入metadata，供後續managed load重新解析；seed、generator settings、structures、bonus chest與forced spawn是建立時交給Paper或寫入世界資料的選項。`--detached`建立世界但不套用Warp/Ownership/Protection治理。MV的alias、game mode、difficulty、auto-load、world price與portal設定不屬於世界生成參數，因此不在此語法內。
- `/wm load <world>`、`/wm load <world> <NORMAL|NETHER|THE_END> --detached`：既有語法載入ACTIVE或DETACHED metadata world；新語法只接受尚無metadata、未載入且具有安全storage claim的world，載入後直接保存DETACHED metadata。`--detached`缺少environment時不可執行。
- `/wm unload <world> [fallback]`：ACTIVE/DETACHED在成功save/unload後持久化`UNLOADED` desired state；失敗時保留原intent。目前已載入但unknown的world也可執行runtime-only unload，不建立或修改metadata。卸載先以Paper `World.save(true)`顯式存檔，成功後才以`save=false`卸載。fallback可為任一不同、唯一且已載入的runtime world，identity在傳送前後都會重驗。
- `/wm remove <world>`：先要求啟用中的Multiverse-Core 5 hook解除外部追蹤並確認`worlds.yml`已保存，再將ACTIVE metadata標記為DETACHED。世界不需先卸載；已載入時玩家留在原地、runtime保持不變，僅停用Warp/Ownership/Protection與自動reconciliation。MV API或保存失敗時不改變WorldManagement metadata或runtime。
- `/wm manage <world>`：將DETACHED metadata重新設為ACTIVE。採用目前runtime loaded/unloaded狀態作為新desired state；若identity不符則拒絕。
- `/wm remove <world> purge confirm`：只永久清除DETACHED世界的metadata，不刪除世界檔案；effective audit policy為`STRICT`時，admission持久化失敗會拒絕purge並保留DETACHED metadata。
- `/wm import <world> <NORMAL|NETHER|THE_END> [--detached]`：只匯入 world container 內具備 `level.dat` 的安全目錄。`--detached`第一次 durable metadata mutation即建立為DETACHED，不會先啟用治理；runtime仍依匯入流程載入，之後可用lifecycle工具操作。
- `/wm delete <world> [fallback] confirm`：每次只刪除一個指定world。除ACTIVE/DETACHED外，目前唯一載入的unknown world會先以exact runtime identity建立`DELETE_AUTO` DETACHED metadata；adoption失敗、identity ambiguous/replaced或runtime消失時安全拒絕，不按名稱刪storage。loaded world第一次confirmed delete完成玩家搬移、save與unload；再次確認時先要求Multiverse-Core停止追蹤並持久化，再將storage移入quarantine、以identity/version/transaction CAS持久化`DELETING`、永久刪除quarantine並purge metadata，包括ephemeral `DELETE_AUTO` record。正常成功在同一runtime回覆永久刪除；只有durable tombstone後遇到shutdown才保留quarantine並回覆下次啟動接續。tombstone前失敗會還原資料；若tombstone寫入期間world被外部重載，runtime會被隔離並回報需停止伺服器檢查live與quarantine資料。
- `/wm tp self <world> [x y z]`：將自己傳送到ACTIVE或DETACHED world spawn或指定座標。ACTIVE套用owner/rank/access治理；DETACHED只要求command permission與verified loaded identity，不套用WorldManagement治理。
- `/wm tp player <online-player> <world> [x y z]`：傳送指定的線上玩家。
- `/wm tp --any <world> [x y z]`：使用顯式 access bypass 進行自我傳送。

以上 lifecycle 指令皆需各自的 `worldmanagement.command.<action>` 權限，預設 OP。`tp player` 另需 `worldmanagement.command.tp.others`，`tp --any` 另需 `worldmanagement.command.tp.any.explicit`。

`/wm` 使用 Paper 的 Brigadier command tree。一般子指令使用 literal 或個別 argument node；Help query、display name及`create` options等真正需要保留多個token的terminal值使用typed或greedy argument。tab completion選取候選時只會取代目前argument。`create`的world、environment與world type是獨立node，最後由typed options parser處理可任意排序的flags；domain handler不解析raw command text。

可在 `commands.yml` 設定 `/wm` 的完整 tree alias，以及 `warp`、`ownership`、`storage` 模組的獨立 root alias。所有 lifecycle 指令仍只能由 `/wm` 或 root alias 呼叫。範例：設定 `warp.aliases: [warp]` 後，`/warp trust ...` 等同 `/wm warp trust ...`，保留原生 Brigadier completion。與既有伺服器命令衝突的 alias 會被跳過並在啟動時警告；變更需重啟後生效。完整契約見 [指令架構](command-architecture.md)。

啟用狀態由 `modules.yml` 控制。停用 `lifecycle`、`warp`、`ownership` 或 `storage` 時，對應 `/wm` command branch 不會註冊給玩家；停用 `protection` 時不會註冊世界保護 listener。

## World Identity 與顯示名稱

- `/wm identity show <world>`：顯示已持久化的Paper key、UUID、environment、seed、structures、目前verification state、lifecycle capability及pending observation。
- `/wm identity sync <world>`：只接受`SYNC_PENDING`。當durable identity相同但snapshot欄位有差異時，以目前觀察值更新metadata並恢復`VERIFIED`。
- `/wm identity accept-replacement <world> confirm <clear-warps|keep-warps>`：只接受`CONFLICT`。明確採用replacement identity，並由管理員選擇清除或保留舊Warp；若replacement key/UUID與其他metadata衝突則拒絕。
- `/wm identity abandon <world> confirm`：只接受尚未`VERIFIED`的ACTIVE world，將其改為`DETACHED`與`UNLOADED` intent，保留metadata與世界資料。
- `/wm display-name set <world> <display-name...>`、`/wm display-name reset <world>`：設定或重設ACTIVE或DETACHED metadata顯示名稱；DELETING與完全unknown world拒絕。display name支援受驗證的MiniMessage，world ID、Paper key與Bukkit runtime名稱不會被改名。

權限分別為`worldmanagement.command.identity.show`、`.sync`、`.accept-replacement`、`.abandon`與`worldmanagement.command.display-name.set|reset`，預設OP。identity未驗證時，entry、Warp與一般lifecycle操作會fail closed；即使玩家具備protection bypass，直接傳送或登入/respawn進入replacement world也會被拒絕或移至安全fallback。

## Warps

- `/wm warp list <world>`
- `/wm warp set <world> <name> <PUBLIC|PRIVATE>`：限目標世界 owner 或 Warp 管理員，且執行者必須站在目標世界。
- `/wm warp delete <world> <name>`
- `/wm warp tp <world> <name>`
- `/wm warp trust <world> <warp> <add|remove> <player-name-or-uuid>`：只接受目前線上玩家名稱或 UUID；metadata 永遠保存 UUID，不會進行離線查詢。

`worldmanagement.command.warp` 預設所有玩家可用。設定與刪除仍要求目標世界 owner 或 `worldmanagement.admin.warp.manage`；trust 另需預設 OP 的 `worldmanagement.command.trust`。`worldmanagement.admin.*` 會包含 Warp 與 ownership 全域管理權限。

將 `warp.enabled` 設為 `false` 時，Warp 與 trust 子命令會直接拒絕，不會讀取或變更 metadata。

## Owner、Rank 與 Access

- `/wm ownership owner set <world> <player-name-or-uuid>`、`/wm ownership owner remove <world>`
- `/wm ownership rank create <world> <rank-id>`、`/wm ownership rank delete <world> <rank-id>`
- `/wm ownership rank set <world> <player-name-or-uuid> <rank-id>`、`/wm ownership rank remove <world> <player-name-or-uuid>`
- `/wm ownership rank perm <world> <rank-id> <add|remove> <permission>`
- `/wm ownership rank toggle <world>`
- `/wm ownership access <world> mode <NONE|WHITELIST|BLACKLIST>`
- `/wm ownership access <world> <add|remove> <player-name-or-uuid>`

以上命令各需 `worldmanagement.command.owner`、`worldmanagement.command.rank` 或 `worldmanagement.command.access`，預設 OP。command permission只代表可以使用該指令，不代表能管理所有世界。

`owner set` 與 `owner remove` 一律另需 `worldmanagement.admin.ownership.manage`；世界 owner 不可自行轉讓或放棄。rank與access操作允許目標世界owner，持有`worldmanagement.admin.ownership.manage`則可管理所有受管世界。`worldmanagement.admin.*`預設OP並包含`worldmanagement.admin.ownership.manage`及`worldmanagement.admin.warp.manage`。`worldmanagement.bypass.protection`只略過保護與進入檢查，不再授予ownership或Warp metadata管理能力。

`ownership.maximum-custom-ranks`強制限制每個世界的自訂rank數量；`OWNER`和`GUEST`是不可刪除的系統rank，且`OWNER`的permissions不可修改。

## Storage Migration

`/wm storage migrate <source> <target> confirm` 需要 `worldmanagement.command.storage`。source 必須是目前的 active provider，target 必須在 `storage-migration.targets` 明確設定。流程會先排空已接受的metadata mutation，migration期間拒絕新mutation，完整驗證來源、只寫入空target並重新讀取驗證aggregate。失敗會清除partial target並重新開放active provider；成功後保持mutation frozen且保留來源不變。它不會切換`storage.provider`，請在成功後立即明確更新設定並重啟。

## 外部權限 Provider

LuckPerms 為optional dependency。`hooks.yml`啟用且LuckPerms服務可用時，Warp的非空`required-permission`會以該玩家目前cached contextual query為基礎，將`world`context替換成已載入且identity相符的目的Bukkit world名稱，再查詢LuckPerms cached permission。這代表玩家可在來源世界沒有權限、但因目的世界context具有權限而使用該Warp。

服務未安裝、hook停用、user/query options不存在、identity不相符或查詢失敗時，此外部permission結果為false，Warp安全拒絕；空`required-permission`不受影響。一般command permission與protection bypass仍使用Paper/Bukkit permission。玩家名稱始終由`OnlinePlayerSnapshot`解析，不做LuckPerms離線lookup，WorldManagement rank也不映射或同步成LuckPerms group。

指令completion只能讀取不可變metadata與線上玩家snapshot，因此Warp候選採保守規則：只列出identity已驗證、沒有`required-permission`，且能由metadata證明對任何玩家皆可使用的PUBLIC Warp。依賴owner、bypass、玩家rank、private trust或外部permission的Warp不會出現在候選清單。玩家仍可手動輸入Warp名稱；執行階段會以玩家snapshot、WorldManagement policy與目的世界context做完整最終授權。

Warp設定、刪除、trust及ownership rank/access的世界參數只讀每秒更新的不可變command authorization snapshot。一般玩家只取得自己擁有的世界候選；持有對應`worldmanagement.admin.ownership.manage`或`worldmanagement.admin.warp.manage`的玩家取得所有受管世界候選。管理員專用的owner set/remove只對ownership管理員提供候選。後續rank ID與Warp名稱也沿用相同scope，未知或尚未進入snapshot的玩家回傳空候選。權限變動最多約一秒才反映在候選中，但執行階段每次都以即時permission與immutable metadata做最終授權。

目前沒有設定Warp `required-permission`的管理指令；此欄位只存在於metadata/provider契約。若透過受控資料遷移或管理工具設定，查詢時仍會套用上述目的世界context規則。

## Multiverse-Core 5

Multiverse-Core為optional dependency；lifecycle hook最低支援5.2.0，因為5.0.x尚無保留Bukkit runtime的`RemoveWorldOptions`。`hooks.yml`啟用且相容API可用時，`remove`與第二次confirmed `delete`會透過`WorldManager`及`RemoveWorldOptions`解除MV追蹤，設定`unloadBukkitWorld(false)`以保留現有Bukkit runtime，並額外確認`saveWorldsConfig()`成功，避免world在MV reload或伺服器重啟後再次載入。WorldManagement不直接修改MV的`worlds.yml`，也不使用deprecated remove overload。

伺服器未安裝MV時此hook為no-op；明確停用時WorldManagement不管理MV追蹤狀態。若已安裝且hook啟用，但版本低於5.2.0、API連線、runtime linkage、remove或設定保存失敗，操作會fail closed，不繼續detach、quarantine或刪除，且失敗會釋放per-world operation供管理員重試。建議讓WorldManagement成為remove/delete的唯一入口；MV的import/create、alias、game mode、difficulty、portal及其他世界政策不會雙向同步。MV remove API會同步觸發Bukkit event與保存YAML，因此必須在Paper global scheduler呼叫，可能產生第三方同步I/O延遲。
