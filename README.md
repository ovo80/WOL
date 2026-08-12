# WOL 唤醒工具

![Java 17](https://img.shields.io/badge/Java-17-orange)
![JavaFX 20.0.2](https://img.shields.io/badge/JavaFX-20.0.2-blue)
![MIT License](https://img.shields.io/badge/License-MIT-green)

> 轻量级 Windows 桌面应用：通过 UDP 发送 Wake-on-LAN 魔术包，唤醒远程计算机。
> 网络 I/O 全部运行在非 UI 线程，支持多设备管理、自定义端口、SRV 自动解析与亮/暗双主题。

## 功能特性

- **多设备管理**：设备列表增删切换，配置即时持久化，重启自动回填
- **自定义目标**：广播地址（IPv4 / IPv6 / 主机名）与目标端口（1-65535）均可配置
- **SRV 模式**：内网穿透端口变化无需重配——输入 SRV 记录名（如 `_wol._udp.example.com`），
  点发送自动解析出目标地址与端口（JDK 内置 DNS，零外部依赖），解析结果回显到端口框
- **连发防丢**：单次点击连发 N 个魔术包（默认 5，可配 1-100，间隔 100ms）
- **亮/暗双主题**：一键切换，偏好自动持久化
- **配置自愈**：旧版本配置自动迁移（程序目录 → 用户目录；单文件 → 设备/设置双文件）
- **输入防护**：MAC 白名单过滤 + Service 层二次校验，非法输入即时提示
- **防重复提交**：发送期间禁用全部操作按钮，完成后统一恢复

## 技术栈

Java 17 · JavaFX 20.0.2 · SLF4J + Logback · JUnit 5 · Maven · jpackage（Windows 自包含打包）

## 快速开始

要求 JDK 17+ 与 Maven 3。

```bash
# 构建（产物：target/wol-1.3.0.jar + target/lib/）
mvn package

# 开发运行
mvn javafx:run

# 运行测试（41 例）
mvn test
```

### 命令行手动运行

```bash
java --module-path "lib\javafx-base-20.0.2-win.jar;lib\javafx-graphics-20.0.2-win.jar;lib\javafx-controls-20.0.2-win.jar;lib\javafx-fxml-20.0.2-win.jar" --add-modules javafx.controls,javafx.fxml -jar wol-1.3.0.jar
```

要求 `target/wol-1.3.0.jar` 与 `target/lib/` 保持同级（JAR 内 Class-Path 指向 `lib/`），
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
tools\jpackage\build-installer.bat     # MSI 安装包：target\dist\WOL-1.3.0.msi
```

`target\dist\WOL\` **整目录拷贝即用，目标机免装 Java**，无控制台窗口。
完整工作流、参数与踩坑记录见 `tools/jpackage/README.md`。

## 使用说明

1. **设备列表**（左侧）：支持多台设备；「＋ 新建」追加一台（立即落盘），选中后在右侧编辑，「删除」移除（至少保留一台）
2. 编辑 **设备名**（可选，方便识别）、**MAC 地址**（`XX:XX:XX:XX:XX:XX`，大小写不限，输入框自动过滤非法字符）
3. 确认 **广播地址**（默认 `10.0.0.255`，可改为 `192.168.1.255` 等当前子网广播地址，也支持主机名/IPv6）
4. 确认 **目标端口**（默认 `9`，可自定义为任意 1-65535 端口）
5. **SRV 模式**（勾选「SRV 模式」）：适用于内网穿透——穿透服务的映射端口每次开机可能变化。
   勾选后「广播地址」变为 **SRV 地址** 输入框（填记录名如 `_wol._udp.example.com`，只填域名
   `example.com` 会自动补前缀），「目标端口」变为 **解析目标** 且不可输入；
   点「发送唤醒包」自动解析 SRV 得到 `地址:端口` 并回显到解析目标框，随后向该地址发送魔术包
6. 确认 **连发次数**（全局设置，默认 `5`，每次点击连发 N 个魔术包）
7. 点击「发送唤醒包」→ 使用**表单当前值**发送（未保存也生效）→ 状态区显示 **「魔术包已发送（连发 N 次）」**
   （WOL 无确认机制，界面只反馈发送结果，不承诺目标已开机）
8. 点击「保存配置」→ 持久化当前设备修改；切换设备时若有未保存修改会弹窗确认
9. 右上角按钮可**亮/暗主题一键切换**，偏好自动持久化

### 配置存储

- **设备数据与软件设置分开存储**：设备列表存 `~/.wol/device.properties`（键 `device.N.name|mac|broadcast|port|srvEnabled|srvName`），
  软件设置（主题、连发次数）存 `~/.wol/settings.properties`（Windows 为 `C:\Users\<用户名>\.wol`）
- 配置目录与程序目录解耦，安装到 `Program Files` 等受限目录也可正常读写
- 可用 `-Dwol.config.dir=<目录>` 覆盖配置目录；首次启动自动迁移旧版本配置
  （程序目录 → 用户目录，单文件 → 双文件拆分）

## 测试（JUnit 5，70 例）

- `WolUtilTest` / `WolServiceTest` / `DeviceConfigTest` / `AppSettingsTest` / `SrvUtilTest` —— 核心逻辑
  （MAC 校验、端口边界、魔术包结构、UDP 单发/连发、配置往返/迁移/原子写入、非法值回退、
  SRV 记录名规范化/值解析/选优），无需图形环境
- `FxmlLoadTest` —— FXML + 双主题 CSS 加载（controller 绑定、initialize、CSS 解析），需要桌面会话
- 全部测试通过 `-Dwol.config.dir` 隔离到临时目录，不触碰真实配置；SRV 单测只覆盖离线路径，
  真实 DNS 查询不纳入单测（依赖外部网络）

## 工程结构

```
wol-tool/
├── pom.xml                              # Maven 构建配置（Java 17 + JavaFX 20.0.2）
└── src/
    ├── main/
    │   ├── java/ad/ovo/wol/
    │   │   ├── Launcher.java            # 主类（普通 main，启动 MainApp；规避 JDK/jpackage FXHelper 检查）
    │   │   ├── MainApp.java             # JavaFX 应用入口（加载 FXML、窗口图标、持久化主题）
    │   │   ├── controller/MainController.java  # 控制器（多设备交互 → 委托 Service）
    │   │   ├── common/config/AppConfig.java    # 跨层公共：常量集中管理（端口/次数/主题标识）
    │   │   ├── common/exception/WolException.java  # 跨层公共：业务异常（消息可直接展示给用户）
    │   │   ├── service/WolService.java  # 业务层：WOL 发送接口（异常契约/副作用/线程安全）
    │   │   ├── service/impl/WolServiceImpl.java  # 业务层实现：UDP 单播/广播发送 + SRV 解析链路
    │   │   ├── service/ConfigService.java # 业务层：持久化（设备/设置双文件读写、迁移、原子写入，纯静态工具类）
    │   │   ├── model/Device.java        # 数据模型：设备（设备名/MAC/广播/端口/SRV 开关与记录名）
    │   │   ├── model/DeviceConfig.java  # 数据模型：设备列表（纯数据）
    │   │   ├── model/AppSettings.java   # 数据模型：软件设置（主题/连发次数）
    │   │   ├── util/WolUtil.java        # 工具层：魔术包构造 + UDP 发送（纯网络）
    │   │   └── util/SrvUtil.java        # 工具层：SRV 记录查询/解析（JNDI DNS，jdk.naming.dns）
    │   └── resources/
    │       ├── icon.png                 # 应用窗口图标（打包进 JAR）
    │       └── ad/ovo/wol/
    │           ├── main.fxml            # 主界面布局（设备列表 + 编辑表单）
    │           ├── css/theme-dark.css   # 深色主题
    │           └── css/theme-light.css  # 浅色主题（同一套组件，变量化配色）
    │           （device.properties 不打包，运行时在用户目录 ~/.wol 自动生成）
    └── test/java/ad/ovo/wol/
        ├── WolUtilTest.java             # JUnit 5：MAC/端口/魔术包结构（参数化）
        ├── WolServiceTest.java          # JUnit 5：广播校验/单发/连发（真实 UDP）+ SRV 参数校验
        ├── DeviceConfigTest.java        # JUnit 5：设备配置往返/迁移/原子写入（临时目录隔离）
        ├── AppSettingsTest.java         # JUnit 5：软件设置持久化/非法值回退/拆分迁移
        ├── SrvUtilTest.java             # JUnit 5：SRV 记录名规范化/值解析/选优（离线路径）
        └── FxmlLoadTest.java            # JUnit 5：FXML + 双主题 CSS 加载
```

## 设计要点

| 关注点 | 实现 |
|--------|------|
| 魔术包 | 6 字节 `0xFF` + MAC 重复 16 次（共 102 字节） |
| 传输 | `DatagramSocket` UDP + `setBroadcast(true)`，目标端口可配置（默认 9） |
| SRV 解析 | JDK 内置 JNDI DNS（`jdk.naming.dns` 模块，零外部依赖）：查 SRV 记录 → 取 priority 最小者 → 目标 A/AAAA 解析 → 单播发送；端口框回显 `IP:端口` |
| 多设备 | `List<Device>`（设备名/MAC/广播/端口/SRV 开关/记录名），`device.N.*` 编号持久化；旧单设备格式自动迁移 |
| 连发 | 每次点击连发 N 个魔术包（默认 5，可配 1-100），间隔 100ms 防丢包 |
| 分层架构 | controller → service（校验/异常转译/日志）→ model / util，常量集中在 `config/AppConfig` |
| 日志 | SLF4J + Logback（`logback.xml`），滚动文件 `~/.wol/logs/wol.log`（保留 7 天 / 50MB），用户可见信息仅走界面 |
| 线程模型 | 发送放入 `Task<Void>` 后台线程；`updateMessage()`（内部 `Platform.runLater`）回显状态，绝不阻塞 FX 线程 |
| 防重复提交 | 发送期间禁用「发送唤醒包」「保存配置」「新建」「删除」按钮，成功/失败/取消后统一恢复 |
| 输入防护 | MAC 输入框 `TextFormatter` 白名单过滤（hex+分隔符）；端口仅数字；发送前 Service 层二次校验 |
| 异常体系 | 业务异常 `WolException`（消息可直接展示）；底层保留 `IllegalArgumentException` / `IOException` |
| 注释规范 | **Google Java Style（Javadoc，中文撰写）**：公开方法标注 `@param` / `@return` / `@throws`（含触发条件）与副作用（I/O / 网络 / 全局状态）；数据契约内联说明（配置键、魔术包结构）；圈复杂度高的方法（如 `validateBroadcast`=16）注释最密；禁用「简单/显然」类主观词 |
| 文案约束 | 状态区只显示「魔术包已发送 / 发送失败：xxx」，无「开机成功」类字样 |
| UI | 窗口图标（icon.png）；双主题 CSS（looked-up 颜色变量），渐变背景/圆角卡片/阴影/hover 动效；设备列表选中高亮；状态横幅分级着色 |
| 配置存储 | **设备与设置分离**：`~/.wol/device.properties`（设备列表，`device.N.*`）+ `~/.wol/settings.properties`（主题/连发次数，`-Dwol.config.dir` 可覆盖；旧版程序目录配置与单文件设置均自动迁移），首启自动创建默认配置；新建/删除设备即时落盘，字段编辑走「保存配置」 |

## 许可证

本项目基于 **MIT License** 开源，可自由使用、修改与再分发，详见 [LICENSE](LICENSE)。

Copyright © 2026 **ovo80**
