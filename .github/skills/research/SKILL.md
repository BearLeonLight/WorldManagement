---
name: research
description: "用於研究 WorldManagement 所需的 Paper、LuckPerms、Adventure、Java 或 Gradle API 與相容性；以一手文件和本 workspace 原始碼建立可追溯結論。"
argument-hint: "研究問題、目標 API、版本或欲驗證的設計決策"
user-invocable: true
disable-model-invocation: false
---

# WorldManagement 技術研究

以可驗證的一手來源回答設計、API 與相容性問題。研究預設為唯讀；只有使用者明確要求保存時才建立研究文件。

## 來源優先順序

1. WorldManagement 的 `AGENTS.md`、`docs/`、build 設定、測試與目前實作。
2. 本 workspace 的 Paper、Paper-docs、LuckPerms、Adventure 原始碼與文件，並確認與目前依賴版本的關係。
3. 官方 PaperMC、LuckPerms、Kyori Adventure、OpenJDK 或 Gradle 文件與 release note。
4. 其他來源僅能作為線索；任何技術結論必須回到可查核的一手來源。

## 流程

1. 將問題縮成可回答的技術判斷，列出版本、使用情境、thread affinity、資料安全與相容性限制。
2. 查閱最接近的官方 API declaration、javadoc、實作或正式文件；不要只依部落格或搜尋摘要推論 API 行為。
3. 對每個結論記錄來源、版本/commit、直接證據與限制。區分已證實、推論與待實驗事項。
4. 涉及 Paper scheduler、world/entity/location、I/O 或 metadata 時，以 `AGENTS.md` 的執行緒與儲存約束評估建議是否可用。
5. 如需持久保存，使用 `docs/research/<yyyy-mm-dd>-<topic>.md`，包含問題、結論、適用版本、證據連結/路徑、風險與尚待驗證項目。不要用研究文件覆蓋現有架構或指令契約。

## 回報

提供簡短結論、建議採用或避免的 API、適用前提、來源與未解決風險。研究本身不修改 production code；若需驗證行為，提出最小 prototype 或聚焦測試建議，並交由相應工作流程執行。