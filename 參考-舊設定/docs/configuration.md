# 設定檔與資料格式說明 (WorldManagement)

本插件所有的設定檔均由 **BoostedYAML** 管理。在修改設定檔時，所有的註解（Comments）與空白行都會被 100% 完整保留，且插件升級時能自動補齊新加入的設定選項。

---

## 1. 主設定檔 `config.yml`

存放於 `plugins/WorldManagement/config.yml`。

```yaml
# ==========================================
#        WorldManagement 全局設定檔
# ==========================================

# 是否啟用除錯模式 (Debug Mode)
# 啟用時，主控台會輸出詳細的世界加載、執行緒狀態與錯誤的完整 Stacktrace
debug: false

# 伺服器的大廳 / 備用世界名稱
# 當某世界被卸載或刪除時，在該世界內的玩家會被安全地自動傳送至此世界
fallback-world: "world"

# 磁碟非同步刪除延遲 (毫秒)
# 卸載完成後，後台執行緒等待多久才開始執行遞迴刪除。
# 預設 1000 毫秒（1秒）以防止 Windows 作業系統延遲釋放檔案鎖定。
io-delay-milliseconds: 1000

# 模組化設定開關
modules:
  # 玩家世界管理模組 (世界擁有權、黑白名單、自訂階級權限保護)
  world-ownership:
    enabled: true
    # 新創建或新匯入的世界，預設是否開啟階級保護 (若無個別世界設定，預設套用此值)
    default-rank-system-status: true
    # 是否允許在個別世界中手動開啟或關閉此功能
    allow-per-world-toggle: true
    # 每個世界最多允許自訂的階級數量限制 (此限制不包含系統內建的 OWNER 與 GUEST)
    max-custom-ranks: 5
  
  # 世界傳送點模組 (Warp 功能)
  world-warp:
    enabled: true
```

---

## 2. 外部插件掛鉤設定 `hooks.yml`

存放於 `plugins/WorldManagement/hooks.yml`。

```yaml
# ==========================================
#        WorldManagement 外部插件 Hooks
# ==========================================

luckperms:
  # 是否開啟 LuckPerms Hook
  # 若啟用且伺服器已安裝 LuckPerms，插件將自動使用 LuckPerms API 接管所有的權限檢查
  # 這樣可以使用 LuckPerms 的權限群組與 Context 上下文來進行世界指令判斷
  enabled: true
```

---

## 3. 個別世界設定檔 `worlds/<世界名稱>.yml`

存放於 `plugins/WorldManagement/worlds/<世界名稱>.yml`。為防止單一設定檔損壞造成全體地圖錯誤，每個世界都有獨立的設定檔。
當您成功創建世界時，插件會自動在 Java 程式碼中生成並寫入此檔案，無需管理員手動編寫。

### 個別世界 YML 格式與註解說明：

```yaml
# 世界的持有者 UUID (如果是系統公用世界則為 "server")
owner: "550e8400-e29b-41d4-a716-446655440000"

# 世界內部階級系統啟用開關
# 若無設定，預設會自動載入 config.yml 的 default-rank-system-status
rank-system:
  enabled: true

# 黑白名單限制模式：NONE (不限制), WHITELIST (白名單), BLACKLIST (黑名單)
access-control:
  mode: "NONE"
  list: [] # 儲存 UUID 清單，例如 ["uuid1", "uuid2"]

# 階級權限定義
# 注意：OWNER 與 GUEST 階級不可刪除、ID 不可變動
ranks:
  OWNER:
    display-name: "世界之主" # 僅可變更顯示名稱，其餘固定
  ADMIN:
    display-name: "副官"
    permissions:
      - build
      - container
      - interact
      - set-spawn
  MEMBER:
    display-name: "居民"
    permissions:
      - build
      - container
      - interact
  GUEST:
    display-name: "遊客"
    permissions: []

# 玩家角色對照表 (存於根目錄，與 ranks 獨立分離)
players:
  "f81d4fae-7dec-11d0-a765-00a0c91e6bf6": "ADMIN"  # UUID -> 階級名稱
  "ab3f48c2-39c8-472e-84df-cd298f2378fa": "MEMBER"

# 傳送點定義
warps:
  spawn:
    x: 0.5
    y: 64.0
    z: 0.5
    yaw: 90.0
    pitch: 0.0
    type: "PUBLIC" # PUBLIC 或 PRIVATE
    allowed-players: []
    allowed-ranks: []
    required-permission: ""
  vip_room:
    x: 15.5
    y: 12.0
    z: -30.0
    yaw: 0.0
    pitch: 0.0
    type: "PRIVATE"
    allowed-players:
      - "uuid-of-trusted-player"
    allowed-ranks:
      - "ADMIN"
    required-permission: "worldmanagement.warp.vip"
```

---

## 4. 自動修補與容錯處理機制

當讀取世界 YAML 時，插件會自動執行以下檢查以防出錯：
1. **缺少系統階級修復**：如果管理員手動修改檔案時不小心刪除了 `OWNER` 或 `GUEST` 節點，插件載入時會自動將其補回並載入預設屬性。
2. **無效玩家階級防禦**：如果 `players` 對照表中的某個玩家指向了一個已被刪除或不存在的階級（例如玩家的階級寫成 `NON_EXISTENT`）：
   * 在 **Debug 模式啟用** 下，控制台會印出完整的檔案行號錯誤與調用堆疊。
   * 在 **一般模式** 下，會印出一行清晰的警告注意事項。
   * 插件**不會套用該名玩家的錯誤設定，將其預設降為 GUEST**，且不會加載該無效玩家，防止插件在遊戲過程中因為 NullPointerException 導致伺服器崩潰。
