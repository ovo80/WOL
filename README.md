# 局域网 WOL 唤醒桌面工具（JavaFX）

![Java 17](https://img.shields.io/badge/Java-17-orange)
![JavaFX 20.0.2](https://img.shields.io/badge/JavaFX-20.0.2-blue)
![MIT License](https://img.shields.io/badge/License-MIT-green)

轻量级跨平台桌面应用：通过 UDP 广播发送 Wake-on-LAN 魔术包，唤醒局域网内的远程计算机。
**企业工程化分层**（controller / service / model / util / config），网络 I/O 全部运行在非 UI 线程，
支持**多设备列表切换**、**自定义端口**与**亮/暗双主题**。

> **版本说明**：Java 17（语言级别）+ JavaFX **20.0.2** + SLF4J/Logback 日志。

## 工程结构

```
wol-tool/
├── pom.xml                              # Maven 构建配置（Java 17 + JavaFX 20.0.2）
└── src/
    ├── main/
    │   ├── java/ad/ovo/wol/
    │   │   ├── MainApp.java             # 入口（加载 FXML、窗口图标、持久化主题）
    │   │   ├── MainController.java      # 控制器（多设备交互 → 委托 Service）
    │   │   ├── config/AppConfig.java    # 常量集中管理（端口/次数/主题标识）
    │   │   ├── exception/WolException.java  # 业务异常（消息可直接展示给用户）
    │   │   ├── service/WolService.java  # 业务层：校验编排/异常转译/日志
    │   │   ├── model/Device.java        # 设备模型（设备名/MAC/广播/端口）
    │   │   ├── model/DeviceConfig.java  # Model：多设备列表 + 全局设置持久化
    │   │   └── util/WolUtil.java        # 底层工具：魔术包构造 + UDP 发送（纯网络）
    │   └── resources/
    │       ├── icon.png                 # 应用窗口图标（打包进 JAR）
    │       └── ad/ovo/wol/
    │           ├── main.fxml            # 主界面布局（设备列表 + 编辑表单）
    │           ├── css/theme-dark.css   # 深色主题（渐变背景/卡片/hover 动效）
    │           └── css/theme-light.css  # 浅色主题（同一套组件，变量化配色）
    │           （device.properties 不打包，运行时在程序目录自动生成）
    └── test/java/ad/ovo/wol/
        ├── SmokeCheck.java              # 冒烟测试（MAC/端口/魔术包/UDP/多设备配置）
        └── FxmlCheck.java               # FXML + 双主题 CSS 加载测试
```

## 构建

```bash
mvn package
```

产物：
- `target/wol-1.1.0.jar` —— 主 JAR（MANIFEST 已含 Main-Class 与 Class-Path）
- `target/lib/` —— 全部依赖（javafx-controls / javafx-fxml 等）

> 本机提示：若 `mvn` 命令报 classworlds 错误，说明 M2_HOME 未生效，可用
> `java -classpath "$MAVEN_HOME/boot/plexus-classworlds-*.jar" -Dclassworlds.conf="$MAVEN_HOME/bin/m2.conf" -Dmaven.home="$MAVEN_HOME" -Dmaven.multiModuleProjectDirectory=<项目目录> org.codehaus.plexus.classworlds.launcher.Launcher package` 代替。

## 运行

### 方式一：开发调试（JavaFX 插件）

```bash
mvn javafx:run
```

### 方式二：分离依赖打包（推荐发布）

**Windows**：

```bash
java --module-path "lib\javafx-base-20.0.2-win.jar;lib\javafx-graphics-20.0.2-win.jar;lib\javafx-controls-20.0.2-win.jar;lib\javafx-fxml-20.0.2-win.jar" --add-modules javafx.controls,javafx.fxml -jar wol-1.1.0.jar
```

**macOS / Linux**：同上，把 4 个 `-win.jar` 换成 `-mac.jar` / `-linux.jar`。

> ⚠️ 为什么不能直接 `java -jar`？JDK 启动器对「主类继承 `javafx.application.Application`」的应用
> 有内置检查：`javafx.graphics` 必须是 **`--module-path` 上的命名模块**，仅放 classpath 会报
> 「缺少 JavaFX 运行时组件, 需要使用该组件来运行此应用程序」后退出（`mvn javafx:run` 无此问题，
> 因为插件会自动配置 module-path）。

要求 `target/wol-1.1.0.jar` 与 `target/lib/` 保持同级（JAR 内 Class-Path 指向 `lib/`）。
将 `target/wol-1.1.0.jar` + `target/lib/` 整体拷贝到目标机器（需安装 JDK 17+）即可运行，
Windows / macOS / Linux 通用（JavaFX 跨平台）。

## 运行测试（无 JUnit 依赖，独立 main）

```bash
# 核心逻辑（MAC 校验、端口边界、魔术包结构、UDP 自定义端口、配置加载）—— 无需图形环境
java -cp "target/classes;target/test-classes;target/lib/*" ad.ovo.wol.SmokeCheck

# FXML + 双主题 CSS 加载（controller 绑定、initialize、CSS 解析）—— 需要桌面会话
java -cp "target/classes;target/test-classes;target/lib/*" ad.ovo.wol.FxmlCheck
```

## 使用说明

1. **设备列表**（左侧）：支持多台设备；「＋ 新建」追加一台（立即落盘），选中后在右侧编辑，「删除」移除（至少保留一台）
2. 编辑 **设备名**（可选，方便识别）、**MAC 地址**（`XX:XX:XX:XX:XX:XX`，大小写不限，输入框自动过滤非法字符）
3. 确认 **广播地址**（默认 `10.0.0.255`，可改为 `192.168.1.255` 等当前子网广播地址，也支持主机名/IPv6）
4. 确认 **目标端口**（默认 `9`，可自定义为任意 1-65535 端口）
5. 确认 **连发次数**（全局设置，默认 `5`，每次点击连发 N 个魔术包）
6. 点击「发送唤醒包」→ 使用**表单当前值**发送（未保存也生效）→ 状态区显示 **「魔术包已发送（连发 N 次）」**（WOL 无确认机制，界面只反馈发送结果，不承诺目标已开机）
7. 点击「保存配置」→ 持久化当前设备修改到 **JAR 同目录**下的 `device.properties`；切换设备时若有未保存修改会弹窗确认
8. 右上角按钮可**亮/暗主题一键切换**，偏好自动持久化

> 注意：配置文件存放在程序所在目录（打包发布时为 JAR 旁）。若该目录无写权限（如安装到系统只读目录），保存会提示失败，请将程序放在可写目录运行。

## 设计要点

| 关注点 | 实现 |
|--------|------|
| 魔术包 | 6 字节 `0xFF` + MAC 重复 16 次（共 102 字节） |
| 传输 | `DatagramSocket` UDP + `setBroadcast(true)`，目标端口可配置（默认 9） |
| 多设备 | `List<Device>`（设备名/MAC/广播/端口），`device.N.*` 编号持久化；旧单设备格式自动迁移 |
| 连发 | 每次点击连发 N 个魔术包（默认 5，可配 1-100），间隔 100ms 防丢包 |
| 分层架构 | controller → service（校验/异常转译/日志）→ model / util，常量集中在 `config/AppConfig` |
| 日志 | SLF4J + Logback（`logback.xml`），控制台输出，用户可见信息仅走界面 |
| 线程模型 | 发送放入 `Task<Void>` 后台线程；`updateMessage()`（内部 `Platform.runLater`）回显状态，绝不阻塞 FX 线程 |
| 防重复提交 | 发送期间禁用「发送唤醒包」「保存配置」「新建」「删除」按钮，成功/失败/取消后统一恢复 |
| 输入防护 | MAC 输入框 `TextFormatter` 白名单过滤（hex+分隔符）；端口仅数字；发送前 Service 层二次校验 |
| 异常体系 | 业务异常 `WolException`（消息可直接展示）；底层保留 `IllegalArgumentException` / `IOException` |
| 文案约束 | 状态区只显示「魔术包已发送 / 发送失败：xxx」，无「开机成功」类字样 |
| UI | 窗口图标（icon.png）；双主题 CSS（looked-up 颜色变量），渐变背景/圆角卡片/阴影/hover 动效；设备列表选中高亮；状态横幅分级着色 |
| 配置存储 | **程序所在目录** `device.properties`（打包态 = JAR 同目录；开发态 = `target/classes`），首启自动创建默认配置；新建/删除设备即时落盘，字段编辑走「保存配置」 |

## 验收对照

- [x] 合法 MAC + 广播地址 + 自定义端口 → 状态区显示「魔术包已发送」（需真实环境验证唤醒）
- [x] 非法 MAC（长度/字符错误）→ 状态区显示具体错误，界面不卡顿
- [x] 端口越界（0 / 65536）、发送次数越界（0 / 101）→ 明确报错提示
- [x] 每次点击连发 N 个包（默认 5），接收端实测 N 个包全部到达且内容一致
- [x] 多台设备增删切换，配置往返读写一致；旧单设备配置自动迁移
- [x] 保存配置 → 重启后自动回填设备列表
- [x] 发包过程中按钮禁用，发送完成后恢复
- [x] 窗口图标 + 亮/暗主题切换并持久化

## 许可证

本项目基于 **MIT License** 开源，可自由使用、修改与再分发，详见 [LICENSE](LICENSE)。

Copyright © 2026 **ovo80**
