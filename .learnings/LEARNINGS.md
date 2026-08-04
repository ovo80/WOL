# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260804-001] knowledge_gap

**Logged**: 2026-08-04T14:14:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
本机 Temurin JDK 17（jdk-17.0.19+10）的 java.net.InetAddress 缺失标准方法 isBroadcastAddress()（JDK 7+ 应存在）。

### Details
javap 验证 + 独立测试程序均报"找不到符号: 方法 isBroadcastAddress()"。这是非标准裁剪 JDK，
代码一旦调用该方法，在编译期即失败（更糟的是在其他机器编译通过、在本机运行时报 NoSuchMethodError）。
JavaFX 24 也因 class 版本 66.0 无法在 JDK 17 上编译/运行（JavaFX 21+ 需要 JDK 21+）。

### Suggested Action
- 避免使用 InetAddress.isBroadcastAddress()，改用字节判断：`addr.length == 4 && (addr[3] & 0xFF) == 255`
- 本机 JDK 17 搭配 JavaFX 20.0.2（class 61.0 兼容）；如用 JavaFX 21+ 需切到 E:\env\Java\jdk-21.0.11+10 或 jdk-25.0.3+9
- 编译可用 JDK 21/25（-source/-target 17），但运行仍受 JDK 17 缺失方法影响，故代码层面规避
- **补充（2026-08-04 二次确认）**：该 JDK 的 `Files.isDirectory/exists/notExists/isReadable` 等也**缺失 `throws IOException` 声明**（javap 验证），multi-catch 中捕获 IOException 会报 "never thrown"。规避：优先用不抛检查异常的 API（如 `Path.toFile().isDirectory()`），或在标准 JDK 上编译验证

### Metadata
- Source: error
- Related Files: E:\home\wol\src\main\java\ad\ovo\wol\WolUtil.java, E:\home\wol\pom.xml
- Tags: jdk17, javafx, incompatibility

---

## [LRN-20260804-002] best_practice

**Logged**: 2026-08-04T14:40:00+08:00
**Priority**: medium
**Status**: pending
**Area**: frontend

### Summary
JavaFX 双主题切换与输入过滤的关键模式（在 WOL 工具重构中验证通过）。

### Details
1. **双主题实现**：两套 CSS 共用类名，颜色用 looked-up 变量（`.root { -accent: #6c5ce7; }` 后 `-fx-background-color: -accent;`），切换时 `scene.getStylesheets().setAll(themeCss)`；自定义变量不要用 `-fx-` 前缀（会与内置属性冲突语义）。
2. **初始化时序坑**：Controller 的 `initialize()` 在 FXML 加载时执行，此时 `getScene()` 为 null —— 初始主题必须在 MainApp 的 `start()`（Scene 创建后）设置，Controller 只负责后续切换。
3. **CSS 语法静默失败**：JavaFX CSS 解析错误不抛异常只打日志。验证方法：FxmlCheck 中 `scene.getStylesheets().add(css) + root.applyCss()`，检查 stderr 无 "CSS Error"。
4. **TextFormatter 白名单过滤**：`change.getControlNewText().matches("(?:[0-9A-Fa-f:\\-])*") ? change : null`，返回 null 整体拒绝变更，简单可靠。
5. **JavaFX 样式类状态切换**：状态横幅分级着色用 `getStyleClass().removeAll("info","success","error") + add(type)`，配合 `.status-banner.info` 等选择器。

### Suggested Action
复用以上模式于后续 JavaFX 项目；注意 Git Bash/GBK 终端下 logback 中文日志显示乱码（输出本身是 UTF-8，非缺陷）。

### Metadata
- Source: conversation
- Related Files: E:\home\wol\src\main\java\ad\ovo\wol\MainController.java, E:\home\wol\src\main\resources\ad\ovo\wol\css\
- Tags: javafx, css, theming, textformatter
- Pattern-Key: javafx.dual_theme_switch

---
