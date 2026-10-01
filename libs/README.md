# libs/ —— 前置模组的本地 jar

这些文件是**第三方二进制**，已被 `.gitignore` 排除（`libs/*.jar`）。本文件说明如何获取与它们的来源。

| 文件 | modId / 版本 | 用途 |
|---|---|---|
| `beyonddimensions-1.21.1-0.7.30.jar` | `beyonddimensions` / 0.7.30 | 主前置：维度网络、存储系统、我们 Mixin 的目标 |
| `projecte-1.21.1-1.1.0.jar` | `projecte` / 1.1.0 | 主前置：EMC 数值与知识（已学习）体系 |
| `create-1.21.1-6.0.10.jar` | `create` / 6.0.10 | **可选**前置：只用于「蓝图接口」集成（让蓝图大炮能用上物化物品）。**只在测试时需要**，不进 `compileOnly` —— 理由见 `docs/VERSIONS.md` 「Create 集成（可选依赖）的接入方式」 |

## 关于 Create 的 flywheel / ponder

**不需要单独下载它们。** `create-1.21.1-6.0.10.jar` 内部已经带了：

```
META-INF/jarjar/flywheel-neoforge-1.21.1-1.0.6.jar
META-INF/jarjar/ponder-neoforge-1.0.82+mc1.21.1.jar
META-INF/jarjar/Registrate-MC1.21-1.3.0+67.jar
```

NeoForge 的 jarJar 机制会在加载 Create 时自动把它们带上，所以 `build.gradle` 不需要声明它们，
也不需要加 Modrinth Maven 仓库。

## 为什么用本地 jar 而不是远程 Maven

Beyond Dimensions 与 ProjectE 两个模组**都没有发布公共 Maven 构件**：

- Beyond Dimensions 的 `publishing` 块只指向本地 `file://` 目录，CI 只发 CurseForge / Modrinth / GitHub Release。
- ProjectE 的 `publishing` 块连上传目标都没有；Maven Central / BlameJared / ModMaven / TerraformersMC / JitPack 等逐个查过全无。
- ProjectE **不在 Modrinth 上**，因此 Modrinth Maven 也拿不到。

（Create 在 Modrinth 上有构件，但它不需要进 `compileOnly`，所以也不需要那个仓库。）

核实过程见 `docs/development/build-and-deps.md` §2。

## 获取方式

| 模组 | 来源 | 需要的文件 |
|---|---|---|
| Beyond Dimensions 0.7.30 | <https://www.curseforge.com/minecraft/mc-mods/beyond-dimensions/files/8812500> 或 <https://modrinth.com/mod/beyonddimensions> | MC 1.21.1 / NeoForge 版本 |
| ProjectE 1.1.0 | <https://www.curseforge.com/minecraft/mc-mods/projecte/files/6611984> | `ProjectE-1.21.1-PE1.1.0.jar` |
| Create 6.0.10（可选） | <https://modrinth.com/mod/create> 或 <https://www.curseforge.com/minecraft/mc-mods/create> | MC 1.21.1 / NeoForge 版本，文件名 `create-1.21.1-6.0.10.jar` |

## 文件名约定

**必须**使用上表中的 ASCII 文件名。原始下载文件名带中文与方括号（例如 `[超越维度] beyonddimensions-...jar`、`[机械动力] create-...jar`），非 ASCII 文件名在 Gradle + Windows 下是已知的编码踩坑点。

## 许可证

三个模组都是 **MIT**（Beyond Dimensions / ProjectE / Create 的 Modrinth 与 CurseForge 页面均为 MIT）：

- Beyond Dimensions © 2025 Frostbite-time
- ProjectE © 2020 Sin Tachikawa
- Create © simibubi

MIT 允许我们把它们作为编译期依赖使用。**不要把这些 jar 打进本模组的发布产物**（既会与玩家自装的版本冲突，也违反 CurseForge / Modrinth 的再分发规则）—— 本项目不启用 `jarJar`。

## 切换依赖方案

`build.gradle` 里同时给出了 CurseMaven 的写法（已核实坐标）。切换时**必须删掉** `files(...)` 那四行再替换，否则同一个模组会被加载两次。
