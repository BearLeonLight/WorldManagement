# 指令與權限指南 (WorldManagement)

本插件提供主指令 `/wm` (或 `/worldmanager`、`/worldmanagement`)。所有指令在執行前皆會進行字元安全檢查（防路徑穿越）與權限過濾。

---

## 權限節點清單 (Permissions)

| 指令 / 功能 | 權限節點 | 預設擁有者 | 說明 |
| :--- | :--- | :--- | :--- |
| `/wm create` | `worldmanagement.command.create` | OP | 創建一個新世界 |
| `/wm delete` | `worldmanagement.command.delete` | OP | 卸載並徹底刪除世界（非同步刪除檔案） |
| `/wm unload` | `worldmanagement.command.unload` | OP | 從記憶體中卸載世界，保留設定 |
| `/wm load` | `worldmanagement.command.load` | OP | 載入已被卸載的世界 |
| `/wm remove` | `worldmanagement.command.remove` | OP | 卸載並從插件紀錄中移除世界（保留地圖檔案） |
| `/wm import` | `worldmanagement.command.import` | OP | 匯入伺服器目錄下的地圖資料夾 |
| `/wm list` | `worldmanagement.command.list` | OP / 玩家 | 列出所有管理的伺服器世界及其狀態 |
| `/wm owner` | `worldmanagement.command.owner` | OP | 設定或移除指定世界之持有者 (Owner) |
| `/wm warp` | `worldmanagement.command.warp` | 玩家 | 設定、刪除、列出、使用傳送點 |
| `/wm trust` | `worldmanagement.command.trust` | 玩家 | 授權玩家使用私有傳送點 |
| `/wm rank` | `worldmanagement.command.rank` | 玩家 | 自訂階級、設定玩家階級、變更階級權限 |
| 管理員 Bypass 旁路 | `worldmanagement.bypass.protection` | OP | 繞過所有世界內部的破壞、放置與開箱保護限制 |

---

## 指令用法與範例

### 1. 世界基本管理指令 (核心模組)

* **`/wm create <世界名稱> [環境類型] [類型] [種子]`**
  * **環境類型** (Environment)：`NORMAL` (主世界), `NETHER` (地獄), `THE_END` (終界) (預設：`NORMAL`)。
  * **類型** (WorldType)：`NORMAL`, `FLAT` (超平坦), `AMPLIFIED` (巨型山地), `LARGE_BIOMES` (大型生物群系) (預設：`NORMAL`)。
  * **種子**：選填。預設為隨機。
  * **範例**：`/wm create custom_flat NORMAL FLAT 12345`

* **`/wm unload <世界名稱>`**
  * **說明**：同步安全保存並卸載世界。如果世界記憶體中還有玩家，會自動將玩家傳送到 `config.yml` 內的大廳世界。
  * **範例**：`/wm unload survival_world`

* **`/wm load <世界名稱>`**
  * **說明**：載入被卸載的世界。會自動將 `keepSpawnInMemory` 設為 `false` 以防伺服器卡頓。
  * **範例**：`/wm load survival_world`

* **`/wm delete <世界名稱>`**
  * **說明**：先同步卸載世界，確認釋放成功後，在後台非同步遞迴刪除地圖資料夾，並移除設定檔。
  * **範例**：`/wm delete old_sandbox`

* **`/wm remove <世界名稱>`**
  * **說明**：先同步卸載世界，成功後從插件設定中移除該世界，但保留硬碟上的地圖資料夾（可用於轉讓或手動備份）。
  * **範例**：`/wm remove backup_world`

* **`/wm import <世界名稱> [環境類型] [類型]`**
  * **說明**：匯入在伺服器目錄下的同名資料夾（必須包含 `level.dat`），並為其自動生成設定檔。
  * **範例**：`/wm import lobby_map NORMAL NORMAL`

* **`/wm list`**
  * **說明**：列出目前所有已登錄世界、其擁有者、載入狀態以及是否開啟階級保護。

---

### 2. 世界擁有者與階級指令 (WorldOwnership 模組)

世界擁有者 (`OWNER`) 或管理員可對其世界執行以下指令：

* **`/wm owner set <世界名稱> <玩家名稱>`**
  * **說明**：(僅管理員) 將指定世界的所有權賦予某個玩家。該玩家會自動成為 `OWNER` 階級，且在該世界的 `players` 中更新為 `OWNER`。
  * **範例**：`/wm owner set plot_1 BearL`

* **`/wm owner remove <世界名稱>`**
  * **說明**：(僅管理員) 將世界擁有者改回系統 `Server`，並停用階級權限限制。

* **`/wm rank create <世界名稱> <階級名稱>`**
  * **說明**：(擁有者/管理員) 新增一個自訂階級。受到最大自訂階級數限制（預設 5 個，排除 OWNER, GUEST）。
  * **範例**：`/wm rank create my_world builder`

* **`/wm rank delete <世界名稱> <階級名稱>`**
  * **說明**：(擁有者/管理員) 刪除指定自訂階級（不可刪除 OWNER 與 GUEST）。
  * **範例**：`/wm rank delete my_world builder`

* **`/wm rank set <世界名稱> <玩家名稱> <階級名稱>`**
  * **說明**：(擁有者/管理員) 將玩家設為指定階級。
  * **範例**：`/wm rank set my_world Alice builder`

* **`/wm rank remove <世界名稱> <玩家名稱>`**
  * **說明**：(擁有者/管理員) 移除該玩家在該世界的階級，該玩家會自動變回預設的 **GUEST (訪客)** 階級。

* **`/wm rank perm <世界名稱> <階級名稱> <add/remove> <權限旗標>`**
  * **說明**：(擁有者/管理員) 調整某個階級擁有的權限旗標。
  * **權限旗標**：`build` (建築/破壞), `container` (開箱熔爐), `interact` (按鈕拉桿門), `set-spawn` (設傳送點), `use-public-spawn` (傳送公開 Warp), `use-private-spawn` (傳送私有 Warp)。
  * **範例**：`/wm rank perm my_world builder add build`

* **`/wm toggle-rank <世界名稱> <true/false>`**
  * **說明**：(擁有者/管理員) 開啟或關閉該世界的階級保護功能。

* **`/wm access <世界名稱> <mode> <whitelist/blacklist/none>`**
  * **說明**：(擁有者/管理員) 切換進出限制模式。
  * **範例**：`/wm access my_world mode whitelist`

* **`/wm access <世界名稱> <add/remove> <玩家名稱>`**
  * **說明**：(擁有者/管理員) 將玩家新增至黑名單或白名單。
  * **範例**：`/wm access my_world add Bob`

---

### 3. 傳送點指令 (WorldWarp 模組)

* **`/wm warp set <世界名稱> <傳送點名稱> <PUBLIC/PRIVATE>`**
  * **說明**：(需有 `set-spawn` 權限) 於目前位置設定一個公開或私有的傳送點。
  * **範例**：`/wm warp set survival spawn PUBLIC`

* **`/wm warp delete <世界名稱> <傳送點名稱>`**
  * **說明**：(需有 `set-spawn` 權限) 刪除指定的傳送點。

* **`/wm warp tp <世界名稱> <傳送點名稱>`**
  * **說明**：傳送至指定的傳送點。若為私有，必須符合：為世界擁有者、在 `allowed-players` 中被 Trust、擁有對應的 Rank 或擁有設定要求的 LuckPerms 權限組。

* **`/wm trust <世界名稱> <傳送點名稱> <add/remove> <玩家名稱>`**
  * **說明**：(擁有者/管理員) 授權或取消授權某個玩家訪問該私有傳送點。
  * **範例**：`/wm trust survival secret_vault add Bob`

---

## 非同步自動補全與權限過濾

當玩家在伺服器輸入 `/wm ` 並按下 Tab 時：
* 系統會在後台非同步執行權限檢索，**玩家只會看見自己擁有權限執行指令**。
* 輸入 `/wm load [Tab]` 時，只會列出被卸載且該玩家有權限載入的世界。
* 輸入 `/wm unload [Tab]` 時，只會列出載入中且該玩家有權限卸載的世界。
