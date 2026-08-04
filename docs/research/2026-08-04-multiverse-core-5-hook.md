# Multiverse-Core 5 Hook 研究

## 問題與版本

WorldManagement在`remove`或永久`delete`時，必須如何讓Multiverse-Core 5停止追蹤world，同時保留WorldManagement自己的unload、quarantine與刪除流程？本研究適用於WorldManagement目前compile-only依賴的Multiverse-Core 5.7.3與Paper 26.2。

## 結論

- 使用`MultiverseCoreApi.get().getWorldManager()`取得公開`WorldManager`，不要直接修改MV的`worlds.yml`。
- 以`WorldManager.removeWorld(RemoveWorldOptions.world(world).unloadBukkitWorld(false))`解除追蹤。`false`保留Bukkit runtime，讓WorldManagement仍是save、玩家搬移、unload與storage deletion的唯一owner。
- remove成功後再明確呼叫並檢查`WorldManager.saveWorldsConfig()`。MV remove實作會嘗試保存，但其remove結果不反映該save結果；只有第二次可觀察save成功，才能證明重啟後不會由`worlds.yml`重新加入。
- remove或save失敗時fail closed。save失敗後保留retryable pending state；下一次同world操作只重試保存，不重複remove。
- API呼叫必須位於Paper global scheduler。MV remove同步觸發Bukkit event，並同步保存YAML；將它移到WorldManagement的I/O worker會違反Bukkit thread affinity。同步I/O造成的tick延遲是目前第三方API限制，需在真實伺服器量測。

## 推薦 Hook 範圍

- `remove`：先解除MV追蹤並確認保存，再將WorldManagement metadata改為`DETACHED`；Bukkit runtime與玩家不變。
- 第二次confirmed `delete`：第一次仍由WorldManagement搬離玩家、save與unload；第二次在quarantine前解除MV追蹤並確認保存，避免MV reload或重啟重新載入。
- `adopt`、`create`、`load`、`manage`：不自動寫入或同步MV設定，避免雙owner與隱式雙寫。
- MV的alias、game mode、difficulty、auto-load、world price、portal與其他policy：不映射到WorldManagement create options；create只提供Paper `WorldCreator`可直接履行的生成資料。

## 證據

- `Multiverse-Core/src/main/java/org/mvplugins/multiverse/core/world/WorldManager.java`：`removeWorld(RemoveWorldOptions)`與`saveWorldsConfig()`實作。
- `Multiverse-Core/src/main/java/org/mvplugins/multiverse/core/world/options/RemoveWorldOptions.java`：`unloadBukkitWorld`選項契約。
- `Multiverse-Core/src/main/java/org/mvplugins/multiverse/core/MultiverseCoreApi.java`：公開API入口。
- `src/main/java/io/github/bearl/worldmanagement/hook/MultiverseWorldTrackingHook.java`：WorldManagement adapter與retryable persistence state。
- `src/test/java/io/github/bearl/worldmanagement/hook/MultiverseWorldTrackingHookTest.java`：adapter focused contract。
- `e2e/console/test/console-command-test.cjs`：真實Paper與Multiverse-Core 5.7.3 runtime；由MV建立world後以WM adopt/remove/delete，解析`worlds.yml`驗證entry消失，並在重啟後再次驗證。

## 已驗證 Runtime

- `paperConsoleCommandTest`使用SHA-256固定的Multiverse-Core 5.7.3 plugin artifact。
- MV建立並追蹤的`archive`經WM adopt/remove後，`worlds.yml`不再包含其`read-only.legacy-world-name`。
- MV建立並追蹤的`basic`經WM adopt、兩次confirmed delete後，在同一runtime移除MV entry、WorldManagement metadata、world storage與quarantine claim。
- Paper重啟後，`archive`與`basic`仍未重新出現在MV `worlds.yml`，刪除資料也未復現。

## 尚待驗證

- 量測大型`worlds.yml`下remove/save在global thread的耗時與MSPT影響。
- 驗證MV `/mv reload`、熱載入、熱卸載或API在plugin lifecycle中失效時的fail-closed診斷。