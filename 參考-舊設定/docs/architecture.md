# 技術架構與效能優化設計 (WorldManagement)

本文件深入解析世界管理插件的核心技術架構、非同步執行緒模型與軍規級的安全防範設計。

---

## 1. 執行緒與並發模型 (Thread Concurrency Model)

本插件將工作流程清晰劃分為：**遊戲主執行緒 (主 Tick 迴圈)** 與 **專屬後台 I/O 執行緒池**，在兩者之間實施嚴密的同步與非同步橋接。

### 核心執行緒結構：
* **伺服器主執行緒 (Server Tick Thread)**：
  - 負責執行所有與 Minecraft 世界載入/卸載相關的安全 API（如 `Bukkit.createWorld`、`Bukkit.unloadWorld`），以避免 Minecraft 引擎內部發生執行緒衝突（Race Conditions）。
  - 負責接收事件監聽（如玩家開箱、破壞方塊）並進行極速快取判定。
* **專屬單執行緒 I/O 執行緒池 (Single-Threaded I/O Executor)**：
  - 由插件在 Java 記憶體中單獨初始化（`Executors.newSingleThreadExecutor`）。
  - 負責排隊處理所有 YAML 存檔作業（`save()`）與 Java NIO 的硬碟檔案遞迴刪除。
  - **好處**：這能確保伺服器硬碟讀寫排隊進行，不與其他插件爭奪 CPU，並保證寫入與刪除不會對遊戲主 Tick (MSPT) 產生任何抖動。
* **非同步事件執行緒 (Async Event Thread)**：
  - 由 PaperMC 核心驅動，觸發 `AsyncTabCompleteEvent` 來異步處理指令補全，在後台完成權限與快取篩選。

---

## 2. 嚴格的世界卸載與刪除工作流

當玩家執行刪除世界指令時，我們絕對不允許在卸載完成前嘗試刪除檔案。以下是嚴格的安全檢查順序：

```
主執行緒: /wm delete <世界> 
   |
   +--> 檢查快取，設定狀態為 "deleting" (鎖定該世界)
   |
   +--> 傳送該世界內的所有玩家至 fallback 大廳世界
   |
   +--> 主執行緒同步執行 Bukkit.unloadWorld(world, true)
   |
   +--> 檢查 unloadWorld 是否成功回傳 true 且 Bukkit.getWorld(world) == null
         |
         +-- [失敗] --> 終止流程，解鎖狀態，回報玩家「卸載失敗」
         |
         +-- [成功] --> 拋送任務至「專屬單執行緒 I/O 執行緒池」
                          |
                          v
                    非同步執行緒: 等待 1000 毫秒 (系統釋放檔案鎖定)
                          |
                          v
                    非同步執行緒: 調用 Java 21 NIO 遞迴刪除該地圖資料夾
                          | (遇到刪除失敗時，會每 200ms 重試一次，最多 3 次)
                          v
                    非同步執行緒: 執行完成，將結果拋送回主執行緒 (GlobalRegionScheduler)
                          |
                          v
                    主執行緒: 從 ConcurrentHashMap 快取與設定中移除，通知管理員成功
```

---

## 3. 性能優化細節 (MSPT & TPS)

### A. 記憶體快取 (Memory Cache)
為了防止監聽器（BlockBreak, BlockPlace, Interact）每 Tick 查詢硬碟，所有資料都在載入時讀入內存的 **`ConcurrentHashMap<String, WorldConfig>`** 中。快取包含世界擁有者、權限旗標、成員與 Warp 資料。快取是唯讀或執行緒安全寫入的，這確保了 0% 的遊戲內 I/O 消耗。

### B. 出生點常駐優化 (`keepSpawnInMemory = false`)
在載入或創立世界時，`world.setKeepSpawnInMemory(false)` 告訴 Minecraft 不要加載出生點區塊。配合 PaperMC 的非同步區塊載入（Async Chunk Loading），伺服器不會在載入世界的瞬間去主執行緒計算大量地圖生成，使加載時間縮短 95% 以上。

### C. 大廳快速通道 (Fast Pass)
在保護監聽器中：
```java
if (worldConfig.owner().equalsIgnoreCase("server")) {
    return; // 直接放行，完全不進行後續玩家 UUID 與 Ranks 的快取檢索
}
```
這項簡單的檢查能讓 Lobby 等高互動的公共世界對 MSPT 的影響完全歸零。

---

## 4. 軍規級路徑安全防禦

為了防止路徑穿越（Directory Traversal）漏洞導致惡意刪除伺服器其他資料夾，我們在 `FileUtil` 中實作了物理路徑父目錄約束檢查：

```java
// 標準化並絕對化路徑
Path targetDir = Bukkit.getWorldContainer().toPath().resolve(worldName).toAbsolutePath().normalize();
Path containerDir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();

// 強制檢查父目錄必須等於 Container 目錄
if (!targetDir.getParent().equals(containerDir)) {
    throw new SecurityException("路徑穿越威脅攔截：目標資料夾不是地圖容器的直接子目錄！");
}
```

這個檢查能徹底避免輸入 `/wm delete ../../` 或使用符號鏈接來繞過安全限制，確保安全。
