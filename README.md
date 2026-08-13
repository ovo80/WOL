# WOL 唤醒工具

![Java 17](https://img.shields.io/badge/Java-17-orange)
![JavaFX 20.0.2](https://img.shields.io/badge/JavaFX-20.0.2-blue)
![MIT License](https://img.shields.io/badge/License-MIT-green)

> 轻量级 Windows 桌面应用：通过 UDP 发送 Wake-on-LAN 魔术包，唤醒远程计算机。
> 网络 I/O 全部运行在非 UI 线程，支持多设备管理、自定义端口、插件化发送模式（SRV 作为首个 Mod）、亮/暗双主题与插件扩展。

## 功能特性

- **多设备管理**：设备列表增删切换，配置即时持久化，重启自动回填
- **自定义目标**：广播地址（IPv4 / IPv6 / 主机名）与目标端口（1-65535）均可配置
- **发送模式（插件化）**：设备可选「普通广播」或插件提供的发送模式；SRV 模式（首个 Mod）
  输入 SRV 记录名（如 `_wol._udp.example.com`），点发送自动解析出目标地址与端口，解析结果回显到端口框
- **连发防丢**：单次点击连发 N 个魔术包（默认 5，可配 1-100，间隔 100ms）
- **设置中心**：主题、语言、模组统一在设置窗口管理（右上角 ⚙ 按钮进入）
- **插件（Mod）体系**：往 `mods` 目录丢 jar 即可加载新功能，设置里可开关
- **主题可扩展**：内置「默认深色」「默认浅色」，往 `resources` 目录丢主题 jar 即可新增配色
- **语言可扩展**：内置「简体中文」，往 `i18n` 目录丢语言 jar 即可新增语言（文案翻译后续接入）
- **配置自愈**：旧版本配置自动迁移（程序目录 → 用户目录；单文件 → 设备/设置双文件）
- **输入防护**：MAC 白名单过滤 + Service 层二次校验，非法输入即时提示
- **防重复提交**：发送期间禁用全部操作按钮，完成后统一恢复

## 技术栈

Java 17 · JavaFX 20.0.2 · SLF4J + Logback · JUnit 5 · Maven · jpackage（Windows 自包含打包）

## 快速开始

要求 JDK 17+ 与 Maven 3。本仓库即主应用本体 `wol-core`（SRV 插件为**独立项目/独立仓库**，见下文「SRV 插件」）。

```bash
# 构建（产物：target/wol-core-1.4.0.jar + target/lib/ 依赖）
mvn package

# 开发运行
mvn javafx:run

# 运行测试（63 例）
mvn test

# 安装到本地 Maven 仓库（供独立插件项目按坐标依赖）
mvn install
```

### 命令行手动运行

```bash
java --module-path "lib\javafx-base-20.0.2-win.jar;lib\javafx-graphics-20.0.2-win.jar;lib\javafx-controls-20.0.2-win.jar;lib\javafx-fxml-20.0.2-win.jar" --add-modules javafx.controls,javafx.fxml -jar wol-core-1.4.0.jar
```

要求 `wol-core-1.4.0.jar` 与 `lib/` 保持同级（JAR 内 Class-Path 指向 `lib/`），
将二者整体拷贝到目标 Windows 机器（需安装 JDK 17+）即可运行。

> **为什么不能直接 `java -jar`？**
> JDK 启动器对「主类继承 `javafx.application.Application`」的应用有内置检查：`javafx.graphics`
> 必须是 `--module-path` 上的命名模块，仅放 classpath 会报「缺少 JavaFX 运行时组件」后退出。
> 自 v1.1.0 起主类为独立 `Launcher`（内部 `Application.launch(MainApp.class)`），
> 规避该检查，兼容 `-jar` / `--module-path` / jpackage 三种启动方式。

## 打包分发（Windows）

### 本地构建

```bash
tools\jpackage\build-app-image.bat     # 自包含目录：target\dist\WOL\（WOL.exe + 精简 JRE + JavaFX）
tools\wix\build-wix-installer.bat      # MSI 安装包（WiX 向导：可自选目录、可选快捷方式/开始菜单）
```

`target\dist\WOL\` **整目录拷贝即用，目标机免装 Java**，无控制台窗口。
MSI 安装包采用自建 WiX 工程（安装向导可自选目录并记忆上次位置、桌面快捷方式与开始菜单
分组可选、升级自动覆盖），完整工作流与踩坑记录见 `tools/jpackage/README.md`。

## 使用说明

1. **设备列表**（左侧）：支持多台设备；「＋ 新建」追加一台（立即落盘），选中后在右侧编辑，「删除」移除（至少保留一台）
2. 编辑 **设备名**（可选，方便识别）、**MAC 地址**（`XX:XX:XX:XX:XX:XX`，大小写不限，输入框自动过滤非法字符）
3. 确认 **广播地址**（默认 `10.0.0.255`，可改为 `192.168.1.255` 等当前子网广播地址，也支持主机名/IPv6）
4. 确认 **目标端口**（默认 `9`，可自定义为任意 1-65535 端口）
5. **发送模式**（设备信息标题右侧下拉框）：默认「普通广播」；若安装了 SRV 插件并启用，可切换「SRV 模式」。
   适用于内网穿透——穿透服务的映射端口每次开机可能变化。切换后「广播地址」变为 **SRV 地址** 输入框
   （填记录名如 `_wol._udp.example.com`，只填域名 `example.com` 会自动补前缀），「目标端口」变为 **解析目标** 且不可输入；
   点「发送唤醒包」自动解析 SRV 得到 `地址:端口` 并回显到解析目标框，随后向该地址发送魔术包
6. 确认 **连发次数**（全局设置，默认 `5`，每次点击连发 N 个魔术包）
7. 点击「发送唤醒包」→ 使用**表单当前值**发送（未保存也生效）→ 状态区显示 **「魔术包已发送（连发 N 次）」**
   （WOL 无确认机制，界面只反馈发送结果，不承诺目标已开机）
8. 点击「保存配置」→ 持久化当前设备修改；切换设备时若有未保存修改会弹窗确认
9. 右上角 ⚙ 按钮打开**设置窗口**，分三个标签页：
   - **主题**：切换内置「默认深色/默认浅色」或外部主题，即时生效并持久化
   - **语言**：切换界面语言（内置简体中文，外部语言 jar 可扩展）
   - **模组**：勾选启用/取消插件，即时生效并持久化

## 插件体系（Mod / 主题 / 语言）

采用类似 Minecraft mod 的「丢 jar 就加载」机制，核心用 JDK 内置 `ServiceLoader`（Mod）与 jar 描述文件（主题/语言），零外部依赖。三个目录位于配置目录下，**首次启动自动创建**：

| 目录 | 用途 | jar 格式 |
|------|------|----------|
| `mods/` | 插件（新功能） | 实现 `ad.ovo.wol.plugin.Mod` 接口 + `META-INF/services/ad.ovo.wol.plugin.Mod` 注册文件 |
| `resources/` | 主题（配色） | 根目录 `wol-theme.properties`（`id`/`name` 必填，`css` 可选默认 `theme.css`）+ CSS 文件 |
| `i18n/` | 语言 | 根目录 `wol-language.properties`（`code`/`name` 必填） |

### Mod 接口

```java
public interface Mod {
    String id();           // 全局唯一标识，持久化启用状态用
    String name();         // 展示名
    String version();      // 版本号
    String description();  // 一句话描述
    default void onEnable(ModContext context) {}  // 启用回调
    default void onDisable() {}                   // 禁用回调
    default SendMode sendMode() { return null; }  // 可选的发送模式扩展
}
```

Mod 实现类须有无参构造器；jar 内放 `META-INF/services/ad.ovo.wol.plugin.Mod` 文件（内容为实现类全限定名）。放入 `mods/` 后重启应用，设置窗口「模组」页即可看到并勾选启用。

### 发送模式扩展点（SendMode）

插件可通过实现 `SendMode` 接口为设备提供「普通广播」之外的发送方式——这是 SRV 模式作为首个真实 Mod 使用的机制：

```java
public interface SendMode {
    String id();                          // 模式唯一标识（如 "srv"），持久化到设备 mode 字段
    String name();                        // 展示名（如 "SRV 模式"）
    String description();                 // 一句话描述
    String broadcastLabel();              // 选中后广播字段 label（如 "SRV 地址"）
    String broadcastPrompt();             // 选中后广播字段 promptText
    String portLabel();                   // 选中后端口字段 label（如 "解析目标"）
    String portPrompt();                  // 选中后端口字段 promptText
    boolean usesPortField();              // false = 不使用端口输入（端口框禁用）
    Target resolve(String modeValue);     // 把模式数据解析为目标地址+端口
}
```

核心只负责「构造魔术包 + 连发」，不感知具体模式语义——插件在 `resolve()` 里完成目标解析（如 SRV 记录查询），返回 `Target`（地址 + 端口 + 回显文本）。

> **设计要点**：`Mod.id()` 与 `SendMode.id()` 语义不同（插件单元 id vs 发送模式 id），且两者都是无参 `String id()`，**不能由同一个类同时实现两个接口**（方法签名冲突）。约定：`Mod` 通过 `sendMode()` 返回一个**独立的** `SendMode` 实现实例（见 `SrvMod` 与 `SrvSendMode`）。

### SRV 插件（首个真实 Mod）

SRV 模式已从核心彻底拆出，成为**独立项目 / 独立 git 仓库**（工程坐标 `ad.ovo.wol:wol-srv-mod:1.4.0`，按坐标依赖本仓库发布的 `wol-core`，mcmod 式的插件开发模式）。插件产物 `wol-srv-mod-1.4.0.jar`：

1. 构建插件：在其仓库执行 `mvn package`（需先 `mvn install` 安装 core 到本地仓库）
2. 把 `wol-srv-mod-1.4.0.jar` 复制到 `~/.wol/mods/`
3. 重启应用，设置窗口「模组」页勾选启用「SRV 模式」
4. 设备信息区「发送模式」下拉框出现「SRV 模式」选项，即可使用

### 主题 jar 示例

`ocean.jar` 内容：

```
wol-theme.properties     # id=ocean \n name=海洋蓝 \n css=theme.css
theme.css                # 配色样式表
```

### 语言 jar 示例

`english.jar` 内容：

```
wol-language.properties  # code=en \n name=English
```

> 说明：语言 jar 当前只做「发现 + 选择 + 持久化」，界面文案的多语言翻译能力在后续版本接入。

### 配置存储

- **设备数据与软件设置分开存储**：设备列表存 `~/.wol/device.properties`（键 `device.N.name|mac|broadcast|port|mode|modeValue`，
  mode 空=普通广播，非空=插件发送模式 id），
  软件设置（主题、语言、连发次数、启用插件）存 `~/.wol/settings.properties`（键 `ui.theme` / `ui.language` / `device.count` / `mod.enabled.<id>`）
  （Windows 为 `C:\Users\<用户名>\.wol`）
- 配置目录与程序目录解耦，安装到 `Program Files` 等受限目录也可正常读写
- 可用 `-Dwol.config.dir=<目录>` 覆盖配置目录；首次启动自动迁移旧版本配置
  （程序目录 → 用户目录，单文件 → 双文件拆分）

## 测试（JUnit 5，63 例）

- `WolUtilTest` / `WolServiceTest` / `DeviceConfigTest` / `AppSettingsTest` —— 核心逻辑
  （MAC 校验、端口边界、魔术包结构、UDP 单发/连发、配置往返/迁移/原子写入、非法值回退、
  发送模式委托、语言与启用插件持久化），无需图形环境
- `PluginManagerTest` / `ThemeManagerTest` / `LanguageManagerTest` —— 插件体系
  （jar 端到端加载、ServiceLoader 发现、发送模式查找、启用/禁用生命周期、主题/语言 jar 解析与回退），无需图形环境
- `FxmlLoadTest` —— FXML + 双主题 CSS 加载（controller 绑定、initialize、CSS 解析），需要桌面会话
- 全部测试通过 `-Dwol.config.dir` 隔离到临时目录，不触碰真实配置；
  插件项目的单测在其独立仓库中维护（离线路径，真实 DNS 查询不纳入单测）

## 工程结构

```
wol/
├── pom.xml                              # 主项目 POM（单项目：应用 + 插件 SPI + 主题/语言）
└── src/
    ├── main/
    │   ├── java/ad/ovo/wol/
    │   ├── Launcher.java            # 主类（普通 main，启动 MainApp；规避 JDK/jpackage FXHelper 检查）
    │   ├── MainApp.java             # JavaFX 应用入口（加载 FXML、初始化插件体系、窗口图标）
    │   ├── controller/MainController.java  # 主界面控制器（多设备交互 → 委托 Service；打开设置窗口）
    │   ├── controller/SettingsController.java  # 设置窗口控制器（主题/语言/模组切换）
    │   ├── common/config/AppConfig.java    # 跨层公共：常量集中管理（端口/次数/主题/语言/插件目录名）
    │   ├── common/exception/WolException.java  # 跨层公共：业务异常（消息可直接展示给用户）
    │   ├── plugin/Mod.java         # 插件 SPI：第三方 jar 实现此接口即可被加载
    │   ├── plugin/ModContext.java  # 插件上下文（配置目录/专属日志）
    │   ├── plugin/SendMode.java    # 发送模式扩展点 SPI（目标解析）
    │   ├── plugin/Target.java      # 发送目标（地址/端口/回显文本）
    │   ├── plugin/PluginManager.java  # 插件加载与生命周期（ServiceLoader 扫描 mods 目录）
    │   ├── plugin/Theme.java       # 主题模型（id/展示名/CSS 地址）
    │   ├── plugin/ThemeManager.java   # 主题发现（内置 + resources 目录主题 jar）
    │   ├── plugin/Language.java    # 语言模型（code/展示名）
    │   ├── plugin/LanguageManager.java  # 语言发现（内置 + i18n 目录语言 jar）
    │   ├── service/WolService.java  # 业务层：WOL 发送接口（异常契约/副作用/线程安全）
    │   ├── service/impl/WolServiceImpl.java  # 业务层实现：UDP 单播/广播发送 + 发送模式委托
    │   ├── service/ConfigService.java # 业务层：持久化（设备/设置双文件读写、迁移、原子写入，纯静态工具类）
    │   ├── model/Device.java        # 数据模型：设备（设备名/MAC/广播/端口/发送模式 mode/modeValue）
    │   ├── model/DeviceConfig.java  # 数据模型：设备列表（纯数据）
    │   ├── model/AppSettings.java   # 数据模型：软件设置（主题/语言/连发次数/启用插件）
    │   └── util/WolUtil.java        # 工具层：魔术包构造 + UDP 发送（纯网络）
    └── resources/
        ├── icon.png                 # 应用窗口图标（打包进 JAR）
        └── ad/ovo/wol/
            ├── main.fxml            # 主界面布局（设备列表 + 编辑表单 + 发送模式下拉）
            ├── settings.fxml        # 设置窗口布局（主题/语言/模组三个标签页）
            ├── css/theme-dark.css   # 深色主题
            └── css/theme-light.css  # 浅色主题（同一套组件，变量化配色）
            （device.properties 不打包，运行时在用户目录 ~/.wol 自动生成）
    └── test/java/ad/ovo/wol/
        ├── WolUtilTest.java             # JUnit 5：MAC/端口/魔术包结构（参数化）
        ├── WolServiceTest.java          # JUnit 5：广播校验/单发/连发（真实 UDP）+ 发送模式委托
        ├── DeviceConfigTest.java        # JUnit 5：设备配置往返/迁移/原子写入（临时目录隔离）
        ├── AppSettingsTest.java         # JUnit 5：软件设置持久化/非法值回退/拆分迁移
        ├── PluginManagerTest.java       # JUnit 5：插件 jar 端到端加载/发送模式查找/启用禁用
        ├── ThemeManagerTest.java        # JUnit 5：内置/外部主题发现、id 解析回退
        ├── LanguageManagerTest.java     # JUnit 5：内置/外部语言发现、code 解析回退
        └── FxmlLoadTest.java            # JUnit 5：FXML + 双主题 CSS 加载
```
> SRV 插件 `wol-srv-mod` 已拆为**独立项目/独立仓库**（不再在本仓库内），
> 按坐标 `ad.ovo.wol:wol-srv-mod` 依赖本仓库发布的 `wol-core`，构建产物为独立 jar，投放 `~/.wol/mods/`。

## 设计要点

| 关注点 | 实现 |
|--------|------|
| 魔术包 | 6 字节 `0xFF` + MAC 重复 16 次（共 102 字节） |
| 传输 | `DatagramSocket` UDP + `setBroadcast(true)`，目标端口可配置（默认 9） |
| 发送模式 | 设备 `mode` 非空时委托 `SendMode.resolve()` 解析目标，核心只负责魔术包 + 连发；SRV 模式（独立 Mod）用 JDK JNDI DNS（`jdk.naming.dns`）查 SRV 记录 → 取 priority 最小者 → 目标 A/AAAA 解析 → 单播发送 |
| 多设备 | `List<Device>`（设备名/MAC/广播/端口/发送模式 mode/modeValue），`device.N.*` 编号持久化；旧单设备格式自动迁移 |
| 连发 | 每次点击连发 N 个魔术包（默认 5，可配 1-100），间隔 100ms 防丢包 |
| 分层架构 | controller → service（校验/异常转译/日志）→ model / util，常量集中在 `config/AppConfig` |
| 插件体系 | `plugin` 包：`Mod` SPI + `ServiceLoader`（零依赖）；`SendMode` 发送模式扩展点；主题/语言用 jar 描述文件发现；Mod 用独立 URLClassLoader 隔离加载 |
| 项目拆分 | 主应用与插件彻底分离：`wol-core`（本仓库，含 SPI）独立发布；`wol-srv-mod`（独立仓库/独立工程）按坐标 `ad.ovo.wol:wol-core` 以 `provided` 依赖 core，运行时由主程序类加载器提供 SPI 类 |
| 日志 | SLF4J + Logback（`logback.xml`），滚动文件 `~/.wol/logs/wol.log`（保留 7 天 / 50MB），用户可见信息仅走界面 |
| 线程模型 | 发送放入 `Task<Void>` 后台线程；`updateMessage()`（内部 `Platform.runLater`）回显状态，绝不阻塞 FX 线程 |
| 防重复提交 | 发送期间禁用「发送唤醒包」「保存配置」「新建」「删除」按钮，成功/失败/取消后统一恢复 |
| 输入防护 | MAC 输入框 `TextFormatter` 白名单过滤（hex+分隔符）；端口仅数字；发送前 Service 层二次校验 |
| 异常体系 | 业务异常 `WolException`（消息可直接展示）；底层保留 `IllegalArgumentException` / `IOException` |
| 注释规范 | **Google Java Style（Javadoc，中文撰写）**：公开方法标注 `@param` / `@return` / `@throws`（含触发条件）与副作用（I/O / 网络 / 全局状态）；数据契约内联说明（配置键、魔术包结构）；圈复杂度高的方法（如 `validateBroadcast`=16）注释最密；禁用「简单/显然」类主观词 |
| 文案约束 | 状态区只显示「魔术包已发送 / 发送失败：xxx」，无「开机成功」类字样 |
| UI | 窗口图标（icon.png）；双主题 CSS（looked-up 颜色变量），渐变背景/圆角卡片/阴影/hover 动效；设备列表选中高亮；状态横幅分级着色 |
| 配置存储 | **设备与设置分离**：`~/.wol/device.properties`（设备列表，`device.N.*`）+ `~/.wol/settings.properties`（主题/语言/连发次数/启用插件，`-Dwol.config.dir` 可覆盖；旧版程序目录配置与单文件设置均自动迁移），首启自动创建默认配置与 `mods`/`resources`/`i18n` 目录；新建/删除设备即时落盘，字段编辑走「保存配置」 |

## 许可证

本项目基于 **MIT License** 开源，可自由使用、修改与再分发，详见 [LICENSE](LICENSE)。

Copyright © 2026 **ovo80**
