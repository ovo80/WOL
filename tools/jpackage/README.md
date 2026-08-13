# jpackage 打包工作流（WOL 唤醒工具）

用 JDK 自带 `jpackage` 把应用打成 **自包含 app-image**：内含精简 JRE + JavaFX 运行时，
目标机器**免装 Java**，整目录拷贝即用，无控制台窗口。

## 为什么用 jpackage

| 对比项 | java -jar（classpath） | jpackage（本方案） |
|--------|------------------------|--------------------|
| 目标机 Java | 必须预装 JDK 17+ | 免装（自带 runtime） |
| JavaFX 模块检查 | 报「缺少 JavaFX 运行时组件」 | javafx 作为命名模块进运行时，无此问题 |
| 控制台窗口 | 有 | 无（GUI 子系统） |
| 图标/版本信息 | 无 | 支持 --icon / --app-version / --vendor |
| 跨平台 | 一套 jar 通吃 | 需在目标平台分别打包 |

限制：**不能交叉打包**——Windows 包只能在 Windows 上生成；Linux/macOS 同理。

## 目录结构

```
tools/jpackage/
├── build-app-image.bat   一键打包（Maven 构建 + staging + jpackage）
├── fetch-jmods.bat       下载 JavaFX jmods（一次性，可重复执行）
└── jmods/                下载的 jmods（不入库）
    └── javafx-jmods-20.0.2/
```

exe 图标使用 `wol-core/src/main/resources/wol.ico`（用户提供的单图 64×64 图标，随源码入库）。

## 使用步骤

### 1. 准备 JavaFX jmods（一次性）

```
tools\jpackage\fetch-jmods.bat
```

从 Gluon 官方下载 `openjfx-20.0.2_windows-x64_bin-jmods.zip`（约 38MB）并解压。
`build-app-image.bat` 检测缺失时会自动调用。

> jmods 是 jpackage/jlink 专用格式（含 native 库），Maven 仓库的 javafx jar 无法代替。

### 2. 一键打包（每次发布）

```
tools\jpackage\build-app-image.bat
```

流程：Maven 构建（自动探测 mvn.cmd → M2_HOME → MAVEN_HOME → java classworlds 直驱）
→ 组装 staging（主 jar + slf4j/logback，javafx 由模块提供，不进 classpath）
→ `jpackage --type app-image` → 产物 `target\dist\WOL\`。

### 3. 产物与部署

```
target\dist\WOL\
├── WOL.exe        双击运行（无控制台）
├── app\           主 jar + 依赖
└── runtime\       精简 JRE（含 javafx 模块）
```

整个 `WOL\` 目录拷到目标机器即可运行；卸载 = 删目录。
配置文件与日志不落在 app\，而在用户目录 `~/.wol/`（可写、与程序目录解耦）：
设备列表 `device.properties` 与软件设置 `settings.properties` 分离存储，
首次启动自动迁移旧版程序目录下的配置并拆分；可用 `-Dwol.config.dir` 覆盖。

### 4. MSI 安装与升级（tools/wix/build-wix-installer.bat）

MSI 为**每用户安装（per-user，免管理员）**，默认安装目录为 `%LOCALAPPDATA%\WOL`，
用自建 WiX 工程（`tools/wix/Product.wxs`，WixUI_Mondo 向导）生成：
- 安装向导可自选安装目录，并默认显示上次安装位置（记录在 `HKCU\Software\ovo80\WOL\InstallDir`）
- 同一 UpgradeCode + MajorUpgrade：高版本安装时自动检测并替换低版本，无需先手动卸载
- 桌面快捷方式与开始菜单分组在向导中可选
- 升级不影响用户配置（`~/.wol/` 独立于程序目录）

## 核心参数说明

| 参数 | 值 | 说明 |
|------|-----|------|
| `--type` | `app-image` | 免 WiX；`--type msi` 生成安装包需 WiX 3.0+ |
| `--main-class` | `ad.ovo.wol.Launcher` | **必须用 Launcher**（见下文坑 1） |
| `--module-path` | jmods 目录 | jlink 的模块来源 |
| `--add-modules` | `javafx.controls,javafx.fxml,java.naming,jdk.naming.dns` | 进运行时镜像的模块（java.naming 是 logback 所需，jdk.naming.dns 是 SRV 解析所需，见坑 2/5） |
| `--icon` | `wol-core/src/main/resources/wol.ico` | Windows exe 图标（需 .ico，png 不行） |

## 踩坑记录

1. **主类不能继承 Application**：jpackage 原生启动器与 JDK 启动器一样，对
   `extends Application` 的主类走 FXHelper 路径，要求主类从**命名模块**加载，
   classpath 应用会报 `Missing JavaFX application class`。
   → 新增 `Launcher` 普通主类，内部 `Application.launch(MainApp.class, args)`；
   pom 的 Main-Class 与 javafx-maven-plugin 同步指向 Launcher。

2. **jlink 精简运行时会缺模块**：logback 的 joran 配置器引用 `javax.naming`
   （java.naming 模块），缺了报 `NoClassDefFoundError: javax/naming/NamingException`，
   应用启动即失败。→ `--add-modules` 补 `java.naming`。

3. **`--dest` 目录已存在会报错**：jpackage 不覆盖，脚本先 `rmdir /s /q` 旧产物。

4. **bat 脚本坑（Windows 批处理）**：
   - `if (...)` 块内的 `echo` 参数**不能含括号**——`(M2_HOME / ...)` 会被 cmd
     当成嵌套块解析，报 `(... was unexpected at this time.)`；
   - bat 必须 **CRLF** 换行（LF 会解析错乱）；中文注释在 GBK 控制台会乱码，脚本全英文。

5. **SRV 解析依赖 `jdk.naming.dns` 模块**：JNDI DNS（`com.sun.jndi.dns.DnsContextFactory`）
   位于该模块，未加入 `--add-modules` 时 jpackage 产物启动后点发送会报
   「SRV 解析不可用：缺少 JDK DNS 模块」。→ 两个 bat 的 `--add-modules`
   均需追加 `jdk.naming.dns`（classpath 直跑模式不受影响，该模块默认在完整 JDK 中）。

6. **MSI 升级装不到旧目录**：jpackage 的 `--win-dir-chooser` 会让每次安装都弹目录选择，
   而 MSI **不记忆用户自定义的安装目录**——升级时默认目录与旧目录不一致，
   表现为「高版本不能覆盖低版本、必须手动选回 WOL 目录」。
   → **v1.3.0 起 MSI 改用自建 WiX 工程**（见下节），支持向导式选目录并记忆上次位置。

## MSI 安装包（自建 WiX 工程，v1.3.0 起）

`tools\wix\build-wix-installer.bat` 用 **WiX 3.x 自建安装工程**替代 jpackage MSI：

```
WOL-1.3.0.msi  约 30MB，双击安装 / 控制面板卸载
```

**安装向导（WixUI_Mondo）**：欢迎 → 许可协议 → **功能选择**（程序主体必装；
桌面快捷方式、开始菜单分组两个可选项）→ **目录选择** → 确认安装。

- **目录选择**：首次安装默认 `%LOCALAPPDATA%\WOL`（per-user，免管理员）可自选；
  升级时默认显示**上次安装目录**（安装时写入 `HKCU\Software\ovo80\WOL\InstallDir`，
  `RegistrySearch` 回读），且同一 UpgradeCode + MajorUpgrade 自动覆盖旧版
- **功能可选**：桌面快捷方式 / 开始菜单分组做成独立 Feature，向导中可勾选取消
- 工程文件：`tools/wix/Product.wxs`（主工程，版本号经 `-dAppVersion` 注入）、
  `zh-CN.wxl`（本地化，**Codepage=936 必需**——不加会报 LGHT0311 中文代码页错误）、
  `LICENSE.rtf`（许可协议页）
- 构建流程：Maven → jpackage app-image（供 heat 采集）→ heat 生成文件清单
  （`-g1` 按目录分组）→ candle → light（`-cultures:zh-CN -sval`）
- 前置：**WiX Toolset 3.x**（`candle/heat/light` 在 PATH，可从
  https://github.com/wixtoolset/wix3/releases 下载 wix311.exe）
- 已验证：本机构建成功（MSI 头为有效 OLE Compound File 格式）
- 注意：未签名 MSI 安装时 SmartScreen 会提示「未知发布者」，个人自用直接忽略即可

## 进阶（可选）

- **Linux/macOS**：同样流程，jmods 换对应平台压缩包（fetch 脚本按平台调整 URL），
  图标格式分别支持 .png/.icns；安装包类型分别用 `--type deb` / `--type pkg`。
- **GitHub Actions 自动打包**：`windows-latest` runner 预装 WiX，配合
  `actions/setup-java`（Temurin 17）+ 下载 jmods + `mvn package` + jpackage，
  打 tag 自动出 msi 并发布到 Release（本工具仅 Windows 自用，单 runner 即可）。
- **减小体积**：runtime 默认约 100MB+；可用 `--strip-native-commands` 等优化，
  或在 jlink 阶段排除 `--strip-debug` 等（jpackage 已默认精简）。
