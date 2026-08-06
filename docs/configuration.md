# 設定與 Metadata

## `modules.yml`

功能模組使用獨立設定檔，所有值預設為 `true`：

```yaml
lifecycle:
  enabled: true
warp:
  enabled: true
ownership:
  enabled: true
protection:
  enabled: true
storage:
  enabled: true
```

首次啟動時，設定 loader 會在 plugin data folder 建立 `modules.yml`；它不會包入 JAR resource。重啟後生效。

`lifecycle` 控制 create/load/unload/remove/import/delete 與 adopt；`warp` 控制 Warp/trust；`ownership` 控制 owner/rank/access；`storage` 控制 migration；`protection` 控制 cache-only entry/build/interact/container治理listener。world identity conflict與`DELETING` runtime的玩家隔離屬lifecycle安全邊界，永遠啟用且不接受一般protection bypass。

## `commands.yml`

Paper command bootstrap 會在首次啟動建立 `commands.yml`。它可設定 `/wm` 的完整 tree aliases，以及 Warp、Ownership、Storage 的獨立 root aliases：

```yaml
commands:
  root-aliases:
    - worldmanager
    - worldmanagement
  help:
    players-enabled: true
  modules:
    warp:
      aliases: []
    ownership:
      aliases: []
    storage:
      aliases: []
```

所有 alias 都必須唯一，不能是 `wm`，也不能含有空白、`/` 或 `:`。若與既有命令衝突，WorldManagement 會跳過該 alias 並在啟動時記錄警告。設定檔變更必須重啟才會生效。Lifecycle 不支援獨立 alias，避免與其他插件的泛用命令衝突。

`commands.help.players-enabled` 預設為 `true`，代表玩家可免 `worldmanagement.command.help` 使用一般 Help，但內容仍依各 command permission 與 module enablement 過濾。設為 `false` 時，玩家需 `worldmanagement.command.help` 或 `worldmanagement.command.help.all`；console/RCON 不受此開關限制。`help.all` 只略過 command permission，不會顯示停用 module。

`config.yml` 選擇唯一有效 metadata provider。只能啟用一種 provider：

```yaml
schema-version: 1
storage:
  provider: YAML
  jdbc-url: ""
  username: ""
  password: ""
lifecycle:
  fallback-world: ""
  deletion-delay-milliseconds: 1000
ownership:
  default-rank-system-enabled: true
  maximum-custom-ranks: 5
warp:
  enabled: true
audit:
  policy: BEST_EFFORT
debug:
  level: OFF
  sinks:
    console: true
    file: true
  areas: [STARTUP, METADATA, AUDIT, IO, COMMAND, LIFECYCLE, WARP, OWNERSHIP, PROTECTION, STORAGE]
  file:
    max-file-size-mib: 10
    retained-files: 5
  privacy:
    include-player-names: false
    include-masked-ip-addresses: false
    include-masked-jdbc-endpoints: false
locale: zh_TW
storage-migration:
  targets: {}
```

### WorldManagement 診斷

`debug.level` 只控制本插件新增的診斷事件，不修改 Paper 或其他插件的 logger。`OFF` 完全停用且不建立專用 writer；`BASIC` 記錄操作邊界、結果、原因與耗時；`VERBOSE` 再加入已解析的 world、warp、UUID、整數 block 座標、provider、數量與版本。既有 warning、error 與固定啟動摘要不受此開關影響。

`debug.sinks.console` 透過 Paper `ComponentLogger` 將 `[Debug/<area>]` 訊息寫入控制台與標準 `latest.log`。`debug.sinks.file` 寫入 `plugins/WorldManagement/logs/debug.log`。兩者可獨立關閉；若 level 為 `OFF` 或 areas 為空，均不產生診斷。設定變更在重啟後生效。

`debug.areas` 是嚴格 allowlist，合法值為：

- `STARTUP`：設定完成後的 storage、module、hook 與 service 階段。
- `METADATA`：load、adopt、update、remove 與 immutable registry publication。
- `AUDIT`：policy、admission、queue rejection 與寫入失敗，不複製 audit payload。
- `IO`：shutdown、executor rejection 與 I/O drain 結果。
- `COMMAND`：canonical action、actor UUID 與參數數量；不記 raw command text。
- `LIFECYCLE`：create、import、load、unload、remove、delete 與操作耗時。
- `WARP`：teleport outcome 與 `VERBOSE` 整數座標。
- `OWNERSHIP`：owner、rank、access 的固定 action 與 metadata mutation 結果。
- `PROTECTION`：每 world/reason 的 30 秒 denied 彙總，不逐事件寫入。
- `STORAGE`：provider 與 migration outcome，不輸出完整 JDBC URL。

未知 level/area、空白 area、`max-file-size-mib` 超出 `1–100` 或 `retained-files` 超出 `1–20` 都會使插件拒絕啟用。重複 area 在大小寫不敏感正規化後去重。空 `areas: []` 明確代表 match-none。

專用檔案採 UTF-8 可讀單行格式。producer 只向容量 4096 的 bounded queue 做非阻塞 `offer`；滿載時捨棄最新診斷並在恢復後寫入 dropped summary。編碼、檔案 append、rotation 與 archive retention 全由單一背景 writer 執行。預設每 10 MiB 輪替並保留 5 份；file sink failure 只產生 rate-limited plugin warning，不影響 command、world lifecycle、metadata 或 audit 結果。

預設診斷允許 world、UUID、canonical action、已解析參數與整數座標。`include-player-names`、`include-masked-ip-addresses`、`include-masked-jdbc-endpoints` 是預設關閉的額外欄位開關。IP 只允許遮罩 network prefix；JDBC endpoint 只允許 scheme、host、port 與 database，移除 userinfo、query 與 fragment。遮罩解析失敗時完全省略原值。密碼、token、raw command、chat、完整 JDBC URL 與完整 metadata payload 永遠不得輸出。

支援的 provider 為 `YAML`、`SQLITE`、`MYSQL`、`MARIADB`。SQL provider 必須設定 JDBC URL；未知 provider 或空 URL 會使 plugin 啟動失敗，不會退回 YAML 或同時寫入多個 storage。每個 YAML 受管世界都保存於：

```text
plugins/WorldManagement/worlds/<world>.yml
```

目前 YAML metadata schema 為 3；載入時會先 preflight 全部頂層 metadata YAML，任何 future schema 都會在修改前 fail closed。存在 schema 1/2 時，I/O executor 會先把全部 metadata YAML 複製到唯一 `worlds/backup/schema-*` snapshot，再逐檔重寫為 schema 3。`world-key` 固定為 `minecraft:<lowercase-id>`；`management-state` 與 `desired-state` 分別保存管理狀態與 runtime intent：

```yaml
schema-version: 3
world-name: creative
world-key: minecraft:creative
management-state: ACTIVE
desired-state: LOADED
owner: server
version: 0
rank-system:
  enabled: true
access-control:
  mode: NONE
  entries: []
ranks:
  OWNER:
    display-name: World Owner
    permissions: []
  GUEST:
    display-name: Guest
    permissions: []
players: {}
warps: {}
```

每個`warps.<name>`項目會保存座標、yaw/pitch、`PUBLIC|PRIVATE` visibility、trusted players/ranks與`required-permission`。`required-permission`預設為空；目前command只建立空值，沒有直接修改此欄位的管理指令。若由受控migration或管理工具提供非空值，只有已載入且identity相符的目的world可進行外部權限查詢。

`version` 由 repository 用於 optimistic version 檢查，管理員不得手動降低它。future config/metadata/JDBC schema 會 fail closed；無效 UUID、遺失 `OWNER`/`GUEST`，或指向不存在 rank 的玩家映射會被視為無法載入的 metadata。

JDBC schema upgrade 前會先在 `plugins/WorldManagement/backups/schema/` 建立備份。SQLite 使用原生 `VACUUM INTO` 產生一致 `.db` snapshot；MySQL/MariaDB 在 repeatable-read transaction 中將 metadata 與 audit rows 匯出為 UTF-8 JSONL，並建立含 row count 與每個檔案 SHA-256 的 manifest，同時以 database advisory lock 序列化 migration。任一備份失敗會中止 schema upgrade；DDL 完成後才更新 version marker；新建 current-schema database 不建立空備份。

SQLite 已由測試驗證。MySQL/MariaDB 使用相同 JDBC adapter，需在目標伺服器提供 JDBC URL 和帳密。可用專用測試資料庫執行實際 adapter 驗證：

```powershell
.\gradlew.bat test --tests io.github.bearl.worldmanagement.storage.jdbc.ExternalJdbcWorldMetadataRepositoryTest `
  -Dworldmanagement.jdbc.url="jdbc:mysql://localhost:3306/worldmanagement_test" `
  -Dworldmanagement.jdbc.username="worldmanagement_test" `
  -Dworldmanagement.jdbc.password="..."
```

未提供 `worldmanagement.jdbc.url` 時，這個 integration test 會跳過。audit 預設以 `BEST_EFFORT` 寫入 `plugins/WorldManagement/audit.jsonl`；可設定 `OFF`、`BEST_EFFORT` 或 `STRICT`。delete、remove（含detached metadata purge）、owner transfer與storage migration在effective policy為`STRICT`時，admission audit必須成功持久化後操作才會開始。點號分層action會繼承最近的parent override，例如`world.remove.purge`會繼承`world.remove`。

`ownership.maximum-custom-ranks` 會限制每個世界可建立的自訂 rank 數；系統 `OWNER` 與 `GUEST` 不計入限制。`warp.enabled: false` 會停用 `/wm warp`，既有 metadata 不會被刪除。

`lifecycle.fallback-world` 可選擇一個啟動時唯一載入的runtime world，不要求WorldManagement metadata；名稱未載入或ambiguous會使WorldManagement fail-closed停用。`unload`/`delete`也可指定任一不同且唯一載入的fallback，並在玩家傳送完成後重驗pinned identity。save失敗會保留loaded world；Paper未提供可供plugin等待的async world-save completion。已載入世界的confirmed delete第一次只完成save/unload；再次confirmed delete會移入quarantine、留下transaction-bound `DELETING` tombstone，接著在同一runtime刪除quarantine並purge metadata。只有durable tombstone後遇到shutdown時才保留claim，由下一次啟動recovery核對identity/version/transaction後接續。`deletion-delay-milliseconds`由global scheduler計時，不占用I/O worker；tombstone前若世界被外部重新載入或mutation失敗，刪除會中止並還原可恢復資料。

首次啟動會建立 `hooks.yml` 與 `messages_zh_TW.yml`。`hooks.yml`提供以下獨立開關：

```yaml
luckperms:
  enabled: true
multiverse:
  enabled: true
placeholderapi:
  enabled: true
miniplaceholders:
  enabled: true
```

`luckperms.enabled`控制optional LuckPerms API整合。服務可用時，Warp `required-permission`使用目的Bukkit world context的LuckPerms cached permission；服務缺失、停用或查詢失敗時，非空外部permission會fail closed。一般command permission與protection bypass仍由Paper/Bukkit判斷。

`multiverse.enabled`控制optional Multiverse-Core 5 lifecycle整合。未安裝MV時不影響操作；明確停用時WorldManagement不會解除MV追蹤，管理員必須自行避免MV重新載入。啟用且MV已安裝時，`remove`與第二次confirmed `delete`會先使用MV公開API移除world、保留Bukkit runtime並確認`worlds.yml`保存；API unavailable、remove或save失敗會fail closed。該MV API同步觸發Bukkit event與YAML保存，依thread affinity必須在Paper global scheduler執行，是已知的第三方同步I/O限制。`locale`決定使用的`messages_<locale>.yml`。

`placeholderapi.enabled`與`miniplaceholders.enabled`分別控制WorldManagement是否向對應的optional plugin註冊`wm` provider。舊版`hooks.yml`缺少這兩個key時，啟動會補上`enabled: true`，不覆寫既有值。依賴未安裝或開關停用不會阻止WorldManagement啟用；啟動摘要會分別顯示`available`、`not installed`、`disabled by hooks.yml`、`API unavailable`、`identifier collision`或`registration failed`。依賴在runtime啟用時會嘗試註冊，停用時會解除WorldManagement自己持有的provider；插件shutdown也會清理註冊。

兩套provider共用短namespace `wm`：

```text
PlaceholderAPI:    %wm_managed_world_count%
                   %wm_world_display_name:creative_world%
                   %wm_current_world_id%

MiniPlaceholders:  <wm_managed_world_count>
                   <wm_world_display_name:creative_world>
                   <wm_current_world_id>
```

MiniPlaceholders會直接將expansion name與key組合成`<wm_key>`；沒有`<miniplaceholders:...>`前綴。WorldManagement不會縮短其他插件擁有的expansion name。

不需世界或玩家context的global placeholders：

| key | 輸出 |
| --- | --- |
| `managed_world_count` | `ACTIVE` metadata數量 |
| `detached_world_count` | `DETACHED` metadata數量 |

需要`:world_id`參數的world placeholders：

| key | 輸出 |
| --- | --- |
| `world_exists` | metadata是否存在，`true`或`false` |
| `world_display_name` | 驗證後的世界顯示名稱 |
| `world_environment` | 小寫environment |
| `world_management_state` | 小寫management state |
| `world_desired_state` | 小寫desired load state |
| `world_identity_state` | 小寫identity verification state |
| `world_lifecycle_capability` | 小寫lifecycle capability |
| `world_runtime_loaded` | exact verified runtime是否唯一載入，`true`或`false` |
| `world_access_mode` | 小寫access mode |
| `world_rank_system_enabled` | `true`或`false` |
| `world_custom_rank_count` | 不含`OWNER`與`GUEST`的自訂rank數量 |
| `world_warp_count` | Warp數量 |

需要玩家audience context的current-world placeholders：

| key | 輸出 |
| --- | --- |
| `current_world_managed` | 玩家是否位於exact verified、唯一載入的`ACTIVE`世界 |
| `current_world_id` | 目前受管世界ID |
| `current_world_display_name` | 目前受管世界顯示名稱 |
| `current_world_environment` | 小寫environment |
| `current_world_management_state` | 小寫management state |
| `current_world_desired_state` | 小寫desired load state |
| `current_world_identity_state` | 小寫identity verification state |
| `current_world_access_mode` | 小寫access mode |
| `current_world_is_owner` | 玩家是否為世界owner，`true`或`false` |
| `current_world_rank_id` | owner為`OWNER`，否則為已指派rank或`GUEST` |
| `current_world_rank_display_name` | 上述rank的顯示名稱 |

Placeholder key大小寫不敏感；`world_id`保持原值並依metadata的canonical ID精確比對。world key缺少參數、global/current key多出參數或未知key視為格式不符。指定世界不存在時，`world_exists`回傳`false`，其他world placeholders回傳空值。玩家context缺失、玩家不在exact verified且唯一載入的`ACTIVE`世界、或audience沒有UUID時，`current_world_managed`與`current_world_is_owner`回傳`false`，其他current-world placeholders回傳空值。PlaceholderAPI對格式不符或未知key回傳`null`，由呼叫端保留或處理原placeholder；MiniPlaceholders對已註冊但參數/context不符的tag插入空Component。

PlaceholderAPI只輸出純文字，因此顯示名稱中的MiniMessage格式不會變成PAPI輸出語法。MiniPlaceholders的world display name保留`DisplayNameValidator`允許的安全Adventure Component格式；其他值以literal Component輸出。callback只讀immutable `RegistrySnapshot`、`LoadedWorldCatalog`與join/world-change/quit事件維護的玩家世界identity snapshot，不執行YAML、JDBC、檔案I/O、future等待或mutable Bukkit world/player存取。metadata cache以snapshot instance失效，大小受已登錄世界數限制。

若其他插件已註冊`wm` identifier，WorldManagement會fail closed保留既有provider，不覆寫或在cleanup時解除對方註冊。WorldManagement目前只向兩套API提供自己的snapshot-only placeholders；`messages_<locale>.yml`不會同步解析任意第三方PAPI或MiniPlaceholders expansion，避免第三方callback在Paper delivery thread執行阻塞工作或把外部字串重新解析為MiniMessage互動標籤。

## MiniMessage 訊息

`messages_<locale>.yml` 的值是受信任的 Adventure MiniMessage template。管理員可使用完整 MiniMessage 標籤，包括顏色、裝飾、hover、click、URL、run command、font、insertion 與 `<reset>`。`format.prefix` 定義共用的可選 `<prefix>`；一般成功、警告與錯誤訊息預設引用，usage、清單及多行輸出可自行省略。

程式提供的 `<world>`、`<warp>`、`<player>`、`<rank>`、`<provider>`、清單與其他動態 placeholder 永遠以 unparsed 純文字 Component 插入。即使動態值包含 `<red>` 或 `<click:...>`，也不會取得 MiniMessage 樣式或互動事件。

每個自訂 key 只能使用對應內建 template 已定義的動態 placeholder。placeholder 拼錯、加入未知 tag、或 MiniMessage 結構無效時，該 key 會記錄警告並回退 JAR 內建繁中 template；其他合法 key 不受影響。

啟動時會在 `PluginIoExecutor` 讀取並驗證 JAR 內建 template 與 data folder 自訂 locale。自訂檔缺少 key 時，WorldManagement 會將 JAR 內建預設值補入 YAML 並保存，既有自訂值不會被覆蓋。單一 template 無法解析時會記錄檔名、key、template 與解析原因，只在本次執行回退到 JAR 內建繁中 template，不會覆寫管理員原始內容；其他合法 key 照常使用。內建 template 無效代表插件發行內容損壞，插件會停止啟用。

玩家與 RCON 直接接收 Adventure Component，由 Paper 負責能力降級。本機控制台的啟動摘要與指令回覆固定序列化為 ANSI 16 色，支援 Windows Terminal、PowerShell 及其他 ANSI 終端；Paper 的 rolling log appender 會移除 ANSI escape，`logs/latest.log` 仍保持純文字。`ProxiedCommandSender` 的回覆依 Paper 的 caller audience 轉送；最終 caller 是玩家時使用該玩家的 entity scheduler，否則使用 global scheduler。循環 proxy 不會遞迴發送。

YAML overwrite/delete 前會建立 `plugins/WorldManagement/worlds/backup/` 備份。單一 YAML 檔無法解析時會移至 `worlds/quarantine/`，其餘完整 metadata 仍可載入。
