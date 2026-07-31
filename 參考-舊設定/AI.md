# AI 開發與協同記憶規範 (WorldManagement)

> [!IMPORTANT]
> 本文件是所有 AI 協同開發助理（例如 Gemini、Claude、ChatGPT、Cursor、GitHub Copilot）的開發核心依據。
> **在撰寫或修改任何專案原始碼前，AI 必須完整讀取並嚴格遵循此文件中的所有架構與效能約制！**

## 1. 核心開發優先級 (Priority Hierarchy)
在撰寫任何代碼或重構邏輯時，必須遵循以下優先級：
1. **標準 Java 21 標準庫** (最優先)：與遊戲邏輯無關的檔案 I/O、執行緒並發、集合處理，皆必須優先使用 Java 21 NIO 與標準並發庫。
2. **PaperMC API**：涉及遊戲內容互動時，優先使用 PaperMC 原生且獨家的非同步/高性能 API（例如 Adventure API、AsyncTabCompleteEvent、Bukkit.getAsyncScheduler()）。
3. **Bukkit API**：只有在 Java 與 PaperMC 皆沒有提供時，才使用 Bukkit 傳統 API。
4. **已廢棄 API 禁令**：**嚴禁使用任何已被 @Deprecated 標記的 API**。

---

## 2. 核心性能約束 (MSPT & TPS Stabilizer)
為確保伺服器穩定在 20 TPS 且不飆高 MSPT，以下邏輯必須嚴格遵守：
* **禁止主執行緒 Block I/O**：
  - 遊戲中發生的任何 YAML 存檔、讀取、備份或地圖檔案刪除，**絕對不允許在主執行緒執行**。
  - 所有檔案刪除、寫入，必須使用插件專屬的單執行緒執行緒池（`ExecutorService`）非同步執行。
  - 遊戲內的權限判斷、黑白單過濾、Warp 傳送點查詢，**100% 讀取記憶體快取**（快取必須使用 `ConcurrentHashMap` 儲存）。
* **極速世界載入**：
  - 在執行世界載入 (`Bukkit.createWorld`) 前後，必須將 `world.setKeepSpawnInMemory(false)`，停用出生點記憶體常駐，徹底避免載入世界時的瞬間卡頓。
* **非同步自動補全**：
  - 自動補全必須監聽 PaperMC 獨家的 `AsyncTabCompleteEvent`，在**後台非同步執行緒**完成權限過濾與補全選項計算，不佔用主執行緒一絲資源。

---

## 3. 安全防禦約束 (Directory Traversal Defense)
* **世界名稱字元過濾**：
  - 所有世界名稱輸入必須先通過正則表達式過濾：`^[a-zA-Z0-9_\-]+$`（只允許字母、數字、底線、減號）。
  - 排除任何 `.`, `/`, `\`, 空格等特殊符號。
* **物理路徑父目錄檢查**：
  - 世界資料夾絕對化路徑後的父目錄，必須完全等於 `Bukkit.getWorldContainer().toPath()`（地圖直接子目錄限制）。
  - 設定檔絕對化路徑後的父目錄，必須完全等於 `plugins/WorldManagement/worlds/`。
* **匯入檢查**：匯入資料夾前必須先驗證 `level.dat` 是否存在，以確認為合法地圖。

---

## 4. 關鍵資料結構
* **世界獨立 YML 儲存**：世界各自儲存在 `worlds/<世界名>.yml`。
* **Ranks & Players 分離結構**：
  - 權限角色在 `ranks` 節點。`OWNER` 與 `GUEST` 階級不可被程式刪除。
  - `OWNER` 的 `permissions` 不可修改，僅能修改其 `display-name`。
  - 玩家 UUID 與其角色對照表，儲存在根目錄的 `players` 節點下。
  - **範例結構**：
    ```yaml
    owner: "uuid-of-owner"
    rank-system:
      enabled: true
    ranks:
      OWNER:
        display-name: "世界之主"
      GUEST:
        display-name: "遊客"
        permissions: []
    players:
      "uuid-of-player-1": "OWNER"
      "uuid-of-player-2": "GUEST"
    ```
