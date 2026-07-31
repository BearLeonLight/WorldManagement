---
name: storage-migration
description: "用於在 YAML、SQLite、MySQL 或 MariaDB 間遷移或修復 WorldManagement metadata，包括 schema migration、驗證、備份與 audit 驗證。"
argument-hint: "來源 provider、目標 provider 與遷移或修復目標"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 儲存遷移

## 使用時機

- 將 metadata 由 YAML 移至 SQLite 或 MySQL/MariaDB。
- 將 metadata 由 SQL 移回 YAML。
- 套用 storage schema migration 或復原失敗的 migration。
- 切換設定的 storage provider 前驗證資料。

## 流程

1. 確認來源與目標 provider、插件版本，以及伺服器備份為最新。
2. 不修改來源資料，驗證 world identifier、aggregate version、owner/rank invariant、player UUID mapping、access-control entry、Warp 與 audit event 可讀性。
3. 經插件 migration workflow 停止新的 metadata mutation。migration 進行時不得直接執行 SQL 或編輯 YAML。
4. 建立目標 schema 或 YAML directory，再經 storage interface 複製完整的 `WorldMetadata` aggregate。
5. 驗證 aggregate count、identifier、optimistic version，並抽樣驗證巢狀 rank、player、access、Warp 與 audit 資料。
6. 將 migration 結果記錄到 audit trail。僅在驗證成功後才可變更設定的 provider。
7. 目標已成功使用且 backup retention period 屆滿前，來源資料必須保持不變。

## 安全規則

- 不得以啟用雙寫作為 migration 捷徑。
- 不得在 Paper 遊戲執行緒進行 migration。
- 不得靜默修復無效的 world 名稱、UUID、rank reference 或 path；必須隔離並回報。
- migration 不得刪除來源資料。
- `/wm storage migrate <source> <target> confirm` 可用後必須使用它；不得以直接變更 storage 繞過其驗證。
