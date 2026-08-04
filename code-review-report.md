# WOL 唤醒工具 — 企业级工程标准代码审查报告

- 审查范围：`src/main/java`（8 个类）、`main.fxml`、`logback.xml`、`pom.xml`、`SmokeCheck.java`
- 审查日期：2026-08-04
- 总体评级：**B+（良好，接近企业级）** — 分层清晰、注释优秀、校验全面，但有 2 个必须修复的数据一致性缺陷

---

## ✅ 修复记录（2026-08-04 全部完成，构建 + 测试验证通过）

| # | 问题 | 修复方式 | 验证 |
|---|------|---------|------|
| 🔴1 | 保存覆盖主题偏好 | `buildConfigFromState()` 基于磁盘配置增量组装（保留 theme） | SmokeCheck 新增「主题持久化往返」用例 ✔ |
| 🔴2 | 配置写入非原子 | `save()` 改临时文件 + `ATOMIC_MOVE`（不支持时回退普通 move） | 新增「无 .tmp 残留」用例 ✔ |
| 🟡1 | 连发 N 次 Socket/DNS | `WolUtil` 拆分 `resolveAddress` / `createBroadcastSocket` / `sendPacket`，`WolService` 单 Socket 连发 | 连发 3 包实测 ✔ |
| 🟡2 | "发送失败：null" | 失败消息兜底异常类名 | 代码走查 ✔ |
| 🟡3 | parseMac 尾随冒号 | `split(":", -1)` 保留尾空串 | 新增「尾随/前导分隔符」用例 ✔ |
| 🟡4 | 删除失败无回滚 | 保存失败恢复被删设备 + 撤掉占位设备 + 重新选中 | 代码走查 ✔ |
| 🟡5 | 版本漂移 | pom 升至 1.1.0；新增 `app.properties` + Maven filtering 注入 `${project.version}`，`AppConfig.APP_VERSION` 运行时读取（单一事实来源） | `target/classes` 产物确认注入 1.1.0 ✔ |
| 🟡6 | 日志不落盘 | logback 增加 RollingFileAppender（按天滚动，保留 7 天 / 50MB，`-DLOG_DIR` 可覆盖） | `logs/wol.log` 实际落盘 ✔ |
| 💭1 | 列表封装泄漏 | `getDevices()` 返回只读视图，新增 `addDevice` / `setDevices` | 新增「只读视图」用例 ✔ |
| 💭2 | MainApp 绕过分层 | 改经 `WolService.loadConfig()` 读主题 | 代码走查 ✔ |

验证：`mvn package` BUILD SUCCESS（`target/wol-1.1.0.jar`）；SmokeCheck ALL PASSED（含 4 组新增回归）；FxmlCheck PASSED；README JAR 名同步 1.1.0。

---

## 🔍 第二轮复审（2026-08-04，修复后）

**结论：修复质量良好，全部改动经走查确认，无新增 🔴/🟡；仅 3 个 💭 级小点，不阻塞。**

### 修复正确性确认（逐项）
- ✅ **🔴1**：`buildConfigFromState()` 基于磁盘配置增量组装——保存路径与主题路径（`onToggleTheme`）现在都从磁盘读、回写完整配置，两条路径不再互相覆盖
- ✅ **🔴2**：`save()` 临时文件 + `ATOMIC_MOVE`，`AtomicMoveNotSupportedException` 回退路径正确；`Files.createDirectories(file.getParent())` 前已确认 parent 非 null（`getConfigDir()` 恒返回绝对路径）
- ✅ **🟡3 单 Socket 连发**：`try-with-resources` 内层抛 `WolException` 时 socket 自动关闭（`DatagramSocket.close()` 无检查异常）；外层 `catch(IOException)` 只兜 Socket 创建失败，两条错误路径互不吞并
- ✅ **🟡5 版本注入**：静态字段初始化顺序正确（`log` → `APP_NAME` → `APP_VERSION`，`loadVersion()` 使用 log 时已初始化）；pom 两个 resource 条目通过 `<exclude>` 互斥，无重复复制
- ✅ **🟡6 日志**：`${LOG_DIR:-logs}` 默认值语法合法，`logs/wol.log` 实测落盘
- ✅ **💭1 封装**：grep 全项目 `getDevices()` 调用点 6 处，全部只读（size/get），无遗漏的变异调用

### 新发现（均为 💭，可选优化）
1. **`WolUtil.sendWOL` 先建 Socket 再解析地址**（单发方法）：广播地址非法时会白建一个 Socket（try-with-resources 会释放，无泄漏，仅浪费）。可把 `resolveAddress` 提前到 `createBroadcastSocket` 之前，与 `WolService` 保持一致。
2. **Service 层校验顺序变化**：`sendWakeUp` 现为 port → count → broadcast → mac → 地址解析。直接调用 Service 的代码（如测试）若依赖"先报 MAC 错误"会受影响；UI 层 `deviceFromForm` 已先校验 MAC/端口，用户感知无差异。
3. **`buildConfigFromState()` 每次保存多一次磁盘读**：语义正确、性能可接受；极端场景（主题切换保存失败后磁盘恢复可写）下磁盘主题可能与 UI 不一致，如需彻底消除可让 Controller 维护 `currentTheme` 字段。

### 已知遗留（有意保留，确认无数据风险）
- `countField`（连发次数）不参与 dirty 标记——它是全局设置且不随设备切换丢失，不弹窗是正确语义
- `Device` 无 `equals/hashCode`——可变对象 + `ObservableList` 引用语义，加入反而可能破坏 SelectionModel 行为，维持现状正确
- tmp 文件固定名 `device.properties.tmp`——单实例应用无风险；多实例并发保存是 last-writer-wins（原子 move 保证文件完整性，只可能丢修改不可能损坏文件）

**复审总评：修复后达到 A- 级，2 个数据一致性缺陷已消除，工程标准达标。**

---

## ✅ 复审遗留 3 项 💭 全部修复（2026-08-04，构建 + 测试再次全绿）

| # | 问题 | 修复方式 | 验证 |
|---|------|---------|------|
| 💭1 | sendWOL 先建 Socket 后解析 | `resolveAddress` / `buildMagicPacket` 提前到建 Socket 之前，非法输入零资源开销 | 构建 + SmokeCheck 29 用例 ✔ |
| 💭2 | Service 校验顺序漂移 | 恢复 MAC → 端口 → 次数 → 广播 → 解析，与 UI 层 `deviceFromForm` 提示优先级一致 | 构建 ✔ |
| 💭3 | 保存路径读盘 + 主题漂移边缘场景 | Controller 新增 `currentTheme` 字段（内存事实源）：`initialize` 加载、`onToggleTheme` 更新、`buildConfigFromState` 直接引用，不再读盘；主题切换仍基于磁盘配置仅改 theme（避免未保存编辑落盘） | 构建 + SmokeCheck + FxmlCheck ✔ |

**终审结论：评级 A，无遗留问题。**

---

## 总体印象

**做得好的地方（值得保持）：**
- ✅ 标准三层架构（Controller → Service → Model/Util），职责边界干净，`WolUtil` 零 GUI 依赖可独立测试
- ✅ 业务异常 `WolException` 与底层异常分层转译，异常消息面向用户设计
- ✅ 常量集中在 `AppConfig`，无魔法数字散落
- ✅ 网络 I/O 全部走后台 `Task`，UI 线程零阻塞；发送期间禁用操作按钮防重入
- ✅ 输入双层防御（TextFormatter 白名单 + Service 层严格校验），MAC/端口/广播地址校验细致
- ✅ 旧版配置自动迁移、配置缺失自动兜底，向后兼容意识好
- ✅ SLF4J+Logback 企业日志标准，关键路径日志完整
- ✅ Javadoc 覆盖率高且言之有物（含设计决策原因，如 JDK 缺失 `isBroadcastAddress` 的替代方案）

---

## 🔴 Blockers（必须修复）

### 1. 保存配置会把主题偏好重置为深色
**位置**：`MainController.buildConfigFromState()`（第 330-336 行）

`buildConfigFromState()` 新建 `DeviceConfig` 时只设置了 `devices` 和 `sendCount`，**`theme` 字段保留构造默认值 `dark`**。用户每点一次「保存配置」「＋ 新建」「删除」，浅色主题偏好就被静默覆盖回深色。

**复现**：切换到浅色主题 → 点「保存配置」→ 重启应用 → 主题变回深色。

**修复建议**：
```java
private DeviceConfig buildConfigFromState() {
    DeviceConfig config = wolService.loadConfig(); // 保留磁盘上的 theme
    config.getDevices().clear();
    config.getDevices().addAll(devices);
    int count = parseCount(countField.getText());
    config.setSendCount(count < 0 ? AppConfig.DEFAULT_SEND_COUNT : count);
    return config;
}
```
> 注意：`onToggleTheme()` 反向操作（loadConfig 后只改 theme 再保存）是正确的，问题只出在正向组装。

### 2. 配置文件写入非原子，存在损坏风险
**位置**：`DeviceConfig.save()`（第 121 行）

`Files.newOutputStream(file)` 直接截断原文件写入。写入过程中程序崩溃/断电/磁盘满 → `device.properties` 变成半残文件，全部设备配置丢失。企业级持久化标准是「临时文件 + 原子移动」。

**修复建议**：
```java
Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
try (OutputStream out = Files.newOutputStream(tmp)) {
    props.store(out, "WOL tool config (auto-generated)");
}
Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```
> `ATOMIC_MOVE` 在不支持的文件系统上抛 `AtomicMoveNotSupportedException`，需 catch 后回退普通 move。

---

## 🟡 Suggestions（应当修复）

### 1. 连发 N 包 = N 次 DNS 解析 + N 次 Socket 创建
**位置**：`WolService.sendWakeUp` 循环调用 `WolUtil.sendWOL`（每次新建 `DatagramSocket` + `InetAddress.getByName`）

连发 100 次就创建 100 个 Socket、解析 100 次主机名。建议提供 `sendWOL(InetAddress, ...)` 重载，在 Service 层解析一次、复用一个 Socket 循环发包。对局域网工具性能影响有限，但属于明显的资源浪费。

### 2. 发送失败兜底可能显示 "发送失败：null"
**位置**：`MainController.onSend` 的 `task.setOnFailed`（第 163-167 行）

`t.getMessage()` 对 `NullPointerException` 等运行时异常返回 null。建议：
```java
String msg = (t == null) ? "未知错误"
        : (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
```

### 3. `parseMac` 接受尾随分隔符的非法 MAC
**位置**：`WolUtil.parseMac`（第 55 行）

`"00:1A:2B:3C:4D:5E:".split(":")` 会丢弃尾部空串，解析为合法 6 组。UI 的 TextFormatter 允许输入冒号，该输入会被放行。建议改用 `split(":", -1)` 保留尾空串，让组数校验自然拦截。

### 4. 删除设备保存失败无回滚
**位置**：`MainController.onDeleteDevice`（第 234-246 行）

对比 `onNewDevice` 失败时 `devices.remove(device)` 回滚，删除失败时设备已从列表移除但磁盘未更新，内存与磁盘状态不一致。建议失败时 `devices.add(index, removed)` 恢复。

### 5. `APP_VERSION` 与 pom.xml 版本漂移
**位置**：`AppConfig.APP_VERSION = "1.1.0"` vs `pom.xml <version>1.0</version>`

注释自称「与 pom.xml 同步」但已漂移。建议用 Maven resource filtering（`${project.version}` 写入 properties）或构建期生成 `Version.java`，单一事实来源。

### 6. 日志无文件持久化
**位置**：`logback.xml`

仅 ConsoleAppender，GUI 应用双击 JAR 运行时无控制台，排障日志全部丢失。建议增加 `RollingFileAppender` 输出到程序目录 `logs/`。

---

## 💭 Nits（可选优化）

1. `DeviceConfig.getDevices()` 直接暴露内部可变 `ArrayList` 引用，破坏封装；可返回 `Collections.unmodifiableList(devices)` 并提供显式增删方法
2. `MainApp.start()` 直接调用 `DeviceConfig.load()` 绕过 Service 层，与「Controller 不接触底层」的分层约定不一致（虽然只是读主题）
3. 连发次数 `countField` 未绑定 dirty 标记，修改后切换设备不提示丢弃（与四个设备字段行为不一致）
4. 测试采用 main 方法冒烟而非 JUnit，CI 集成和断言报告能力受限；核心校验逻辑很适合迁移到 JUnit 5 参数化测试
5. `Device` 未实现 `equals/hashCode`，目前依赖引用相等碰巧正确，将来列表去重/查找易踩坑
6. `FXML` 根节点 `prefWidth="720"` 与历史约定的 660 不一致（功能无影响，提示确认）

---

## 检查清单结论

| 维度 | 结论 |
|------|------|
| 正确性 | ⚠️ 主题覆盖 bug + 尾随冒号放行 |
| 安全性 | ✅ 无注入面/无敏感数据；输入校验双层防御 |
| 可维护性 | ✅ 分层/注释/常量管理优秀；版本号需单一来源 |
| 性能 | 🟡 连发场景 Socket/DNS 重复开销 |
| 可靠性 | ⚠️ 配置写入需原子化；日志需落盘 |
| 测试 | 🟡 冒烟测试覆盖核心链路，建议迁移 JUnit |

**下一步**：优先修复 2 个 🔴（改动量都很小，合计约 20 行），随后处理连发 Socket 复用与日志落盘。
