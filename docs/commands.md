# 指令

## `/wm help [page|command path]`

`/wm help` 顯示第 1 頁，每頁最多 6 個目前 sender 可見的頂層 topic；`/wm help <page>` 使用 1-based 頁碼；`/wm help <完整指令路徑>` 可查詢 group 或 leaf，例如 `/wm help ownership rank set`。command path 大小寫不敏感，Help completion 直接走訪實際 command specification。

一般 Help 只顯示 sender 具有 command permission 且 module 已啟用的項目。`worldmanagement.command.help.all` 可查看所有已啟用 module 的指令說明，但不會略過 module enablement；不可見 topic 與不存在 topic 使用相同回覆，避免洩漏權限資訊。`worldmanagement.command.help` 與 `.help.all` 預設 OP，console/RCON 始終可使用 Help；玩家免權限預設值由 `commands.help.players-enabled` 控制。

已知指令缺少參數、固定 literal 錯誤或 terminal 後出現多餘參數時，Brigadier 會依同一 specification 回覆最近可見的 canonical usage 與 `/wm help <topic>` 提示。這些錯誤不會落到全域 unknown-command handler，也不解析 raw command text。

## `/wm adopt <world>`

權限：`worldmanagement.command.adopt`，預設 OP。

將一個已載入、尚未被 WorldManagement 登錄的世界加入管理。世界名稱只能包含英文字母、數字、底線和減號。

此指令只建立 WorldManagement metadata，不會改變世界檔案、Paper 載入狀態或 Multiverse 等外部工具設定。

## `/wm list [detached]`

權限：`worldmanagement.command.list`，預設 OP。

列出 WorldManagement 記憶體快取中的受管世界；加入 `detached` 可列出已停止管理、但仍保留 metadata 的世界。每個世界使用 `<world-id或顯示名稱> - <NORMAL|NETHER|THE_END|CUSTOM>` 格式逐行顯示；設定顯示名稱後以該名稱為主，游標停留時顯示不可變的 world ID。此指令不讀取 YAML、SQL 或 Bukkit world。

## 訊息輸出

所有 `/wm`、root alias 與 module alias 回覆都使用 Adventure Component，內容由 `messages_<locale>.yml` 的 MiniMessage template 控制。控制台與 RCON 由 Paper 正常呈現或降級；代理指令依最終 caller audience 選擇正確的 player entity scheduler 或 global scheduler。

管理員可在 template 使用完整 MiniMessage 標籤。命令參數、metadata 名稱、玩家名稱、UUID 與清單等動態值固定以純文字 placeholder 插入，不能注入顏色、click 或 hover 事件。自訂 locale 的單一 key 缺失或無效時，只回退該 key 到 JAR 內建繁中 template。

## World Lifecycle

- `/wm create <world> <NORMAL|NETHER|THE_END> <NORMAL|FLAT|AMPLIFIED|LARGE_BIOMES> [--seed <seed>] [--generator <plugin[:id]>]`：environment與world type為必填。`--seed`與`--generator`可任意排序、各只能出現一次；已輸入的flag不再出現在後續completion。generator completion只讀取主執行緒更新的有效plugin名稱不可變snapshot，仍可手動輸入`plugin:id`。generator reference會寫入metadata，後續load與補償reload無法重新解析時會拒絕載入，不會退回vanilla生成器。
- `/wm load <world>`、`/wm unload <world> [fallback]`：metadata desired state 會先持久化，再由 Paper global scheduler 嘗試載入或卸載。卸載有玩家時，`fallback`（若指定）必須是不同且已載入的世界；未指定時使用設定 fallback 或 Paper primary world。卸載會先顯式存檔，存檔失敗時保留已載入世界並回報失敗。
- `/wm remove <world>`：只接受 desired state 為 `UNLOADED` 的世界，將 metadata 標記為 `DETACHED`，保留世界檔案與 metadata。
- `/wm manage <world>`：將 `DETACHED` metadata 重新設為受管理的 `ACTIVE` 世界。
- `/wm remove <world> purge confirm`：只永久清除 `DETACHED` 世界的 metadata，不刪除世界檔案；effective audit policy為`STRICT`時，admission持久化失敗會拒絕purge並保留`DETACHED` metadata。
- `/wm import <world>`：只匯入 world container 內具備 `level.dat` 的安全目錄。
- `/wm delete <world> [fallback] confirm`：刪除受管世界。若世界已載入且有玩家，會優先使用指定的 `fallback`，否則使用設定 fallback 或 Paper primary world；玩家全數成功傳送後才會顯式存檔並卸載。此時世界檔案與metadata仍保留，指令會要求再次執行相同confirmed delete。第二次操作確認世界仍未載入後，會在I/O executor移入quarantine、持久化`DELETING` tombstone，接著於同一runtime永久刪除storage並purge metadata。若tombstone後的永久storage delete或metadata purge失敗，指令會回報等待重啟並保留可由startup recovery完成的狀態；tombstone前的存檔、卸載、metadata更新、重新載入檢查或補償失敗仍會保留或還原可恢復資料。
- `/wm tp self <world> [x y z]`：將自己傳送到受管世界 spawn 或指定座標。
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
- `/wm display-name set <world> <display-name...>`、`/wm display-name reset <world>`：設定或重設metadata顯示名稱。display name支援受驗證的MiniMessage，world ID、Paper key與Bukkit runtime名稱不會被改名。

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
