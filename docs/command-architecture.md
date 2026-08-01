# 指令架構契約

WorldManagement 的唯一 canonical root 是 `/wm`。所有 lifecycle 指令只能位於 `/wm` 下；不能設定 lifecycle 的獨立 alias。Warp、Ownership 與 Storage 模組可設定獨立 root alias，但這些 alias 必須轉換為同一條 canonical route，絕不可讓 domain handler 依 command label 或原始 command text 判斷語意。

`WorldManagementCommandSpec` 是 command 結構的唯一來源。`BrigadierCommandSpecCompiler` 由它建立 canonical/root alias/module alias trees；Help topic、usage與錯誤導引、permission/module metadata、completion binding及canonical execution也都直接讀取同一 spec。locale YAML 只保存由stable node ID推導的description與版面文案，不得另建 Help command list。

## 登錄與別名

`WorldManagementBootstrap` 在 Paper `PluginBootstrap` 的 `LifecycleEvents.COMMANDS` 階段載入 `commands.yml`，註冊 `/wm`、其 root aliases 及各 module alias。root aliases 複製完整 `/wm` tree；module aliases 只註冊所屬 module tree。

別名不得是空白、含有空白字元、`/` 或 `:`，也不得與 `wm` 或同一份設定中的任何別名重複。註冊時若 label 已存在，WorldManagement 必須跳過該 alias 並記錄警告，不能覆寫其他插件命令。重新啟動後才會套用 `commands.yml` 變更。

## Canonical 路由

`CommandRoute` 只能由 Brigadier 已解析的 argument 建立，格式為 `prefix + parsed arguments + fixed suffix`。禁止使用 `CommandContext#getInput()`、`split()` 或 command label 推導 route。範例：

- `/wm warp trust creative spawn add Player` 與 `/warp trust creative spawn add Player` 都會傳入 `warp trust creative spawn add Player`。
- `/wm storage migrate YAML SQLITE confirm` 會傳入 `storage migrate YAML SQLITE confirm`。
- `/wm delete creative world confirm` 會傳入 `delete creative world confirm`；不提供 `delete creative confirm world` 平行語法。
- `/wm remove archive purge confirm` 會傳入 `remove archive purge confirm`，因此 `purge confirm` 必須是 parsed world argument之後的 fixed suffix。

新增、刪除或修改語法時，先修改唯一 command spec 的node、access、suggestion與`CommandExecution`，再同步domain handler、locale description、文件與聚焦測試。usage由spec path產生，不在locale或handler重複手寫。舊 syntax 的移除必須明確記錄為不相容變更，不能留下平行隱式 route。

## 權限與補全

每個 Brigadier literal/argument branch 都要以 `CommandAccessPolicy` 施加 module enablement 與對應 command permission。domain handler 仍負責參數相依的 owner、rank、world access 與 audit admission；不可把這些動態授權搬到 command tree。

completion 必須只使用 `SuggestionCatalog`、`WorldRegistry` 的不可變 metadata snapshot、`OnlinePlayerSnapshot`與`CommandAuthorizationSnapshot`。providers 不得讀取 YAML/JDBC/檔案、不等待 future，也不得存取可變的 Bukkit world、location 或 entity。新增 completion source 時，必須宣告 `SuggestionKey<T>` 並以 selector 限制到該 command/argument 的權限與可見範圍。

`/wm help` 與`/wm help <query...>`是spec中的兩個canonical executable paths。query由Brigadier `StringReader`解析為1-based page或完整literal path；Help頁面、topic與completion依sender command permission及module enablement過濾。`worldmanagement.command.help.all`可略過command permission，但不能略過module enablement。不可見與不存在topic必須回覆相同結果。

compiler會為root、中間節點、固定literal位置與terminal節點產生missing/invalid/extra feedback executors。feedback只攜帶最近literal topic path，runtime再由permission-filtered Help service取得canonical usage；不得解析`CommandContext#getInput()`或註冊全域`UnknownCommandEvent`。generated feedback executors不是domain leaves，不列入canonical runtime manifest，但其Paper可觀察行為必須由console runtime matrix覆蓋。

`CommandAuthorizationSnapshot`每秒在玩家所屬scheduler讀取ownership與Warp管理權限，completion只依sender identity查詢快照，再與不可變metadata owner欄位交集。一般owner只取得自己持有的world；對應管理員取得所有受管world；未知玩家fail closed。owner專用管理指令只接受`worldmanagement.admin.ownership.manage`scope。權限快照延遲只影響候選，domain handler仍以即時permission與metadata保留最終授權。

`worldmanagement.admin.ownership.manage`與`worldmanagement.admin.warp.manage`分別代表全世界metadata管理；`worldmanagement.admin.*`集中授予兩者。`worldmanagement.bypass.protection`只控制保護與entry bypass，不得視為metadata管理權限。

玩家參數接受線上玩家名稱或 UUID；名稱必須由 `OnlinePlayerSnapshot` 解析，持久化資料仍只保存 UUID。不得在 command 或 completion 時進行離線玩家 lookup。

`create`固定先解析world、environment與world type，再由terminal typed argument解析`--seed`及`--generator`。flags可任意排序但不可重複，completion只提供尚未使用的flag；generator候選來自主執行緒探測後發布的immutable plugin snapshot，執行階段仍在Paper global thread重新解析`plugin[:id]`。generator無效、停用、回傳null或拋出例外時必須fail closed，且持久化reference供後續managed load重新套用。

## 最低驗證

每個可見的 command 變更至少要有：

- alias/configuration validation test；
- canonical route 或 parser test，證明 alias 與 `/wm` 產生相同 route；
- permission/module visibility test；
- completion test，證明候選只取代目前 argument 且不暴露無權項目；
- name-or-UUID 變更時的 snapshot resolver test。

環境允許時執行 `./gradlew.bat check`；命令 bootstrap、descriptor 或 shadow JAR 有變更時，也執行 `./gradlew.bat paperJarSmokeTest`。需要實際 Paper、但可由 console sender、log、metadata 或 filesystem state 完整證明的指令使用 `./gradlew.bat paperConsoleCommandTest`；只有真實玩家 sender、`OnlinePlayerSnapshot`、位置、世界傳送、protection event、identity隔離或fallback relocation不可替代時才使用`./gradlew.bat paperPlayerE2eTest`。這些task優先使用相鄰`TestServer/`的Paper JAR，否則從官方Fill v3 API下載匹配版本並驗證SHA-256；可用`-PpaperServerJar=<path>`明確覆寫。player E2E需要的Via artifacts驗證SHA-256，LuckPerms plugin artifact驗證SHA-512。

`e2e/command-runtime-coverage.txt` 必須為每個 Brigadier executable path 宣告最低 runtime 層級。`BrigadierWorldManagementCommandTest` 會從實際 command tree 列舉 leaves，拒絕未分類、重複或未知層級；console 與 player runner 必須以 canonical leaf ID 回報實際覆蓋。純 parser、route、permission、completion 與 domain contract 仍由聚焦 JUnit 驗證，runtime outcome 數量不得代替 executable-leaf coverage。

目前runtime baseline為29個console leaves與15個player leaves。console runner驗證47個console command outcomes；player runner驗證27個player command outcomes，另有不計入此數字的event與final-state assertions。這些outcome數量不改變leaf coverage的集合契約。player傳送、identity與protection結果優先使用Paper server-side event、dimension、block或metadata final state；localized聊天訊息不能作為唯一成功證據。Warp completion不讀sender或外部permission，只列能由immutable metadata證明對任何玩家皆可使用的候選；玩家特定與`required-permission` Warp的最終授權保留在command execution。