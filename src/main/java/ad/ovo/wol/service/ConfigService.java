/*
 * WOL 唤醒工具 - 配置持久化（设备列表与软件设置的读写、迁移与原子写入）。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.service;

import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.model.AppSettings;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置持久化：设备列表与软件设置的读写、旧格式迁移与原子写入（纯静态）。
 *
 * <p>文件布局（目录由系统属性 {@code wol.config.dir} 覆盖，默认 {@code ~/.wol}，Windows 为 {@code
 * C:\Users\<用户名>\.wol}）：
 *
 * <ul>
 *   <li>{@code device.properties}（UTF-8）：设备列表，键 {@code
 *       device.N.name|mac|broadcast|port|srvEnabled|srvName}（N 从 1 起）；兼容旧单设备键 {@code
 *       device.mac|broadcast|port}
 *   <li>{@code settings.properties}（UTF-8）：软件设置，键 {@code ui.theme}（dark|light）与 {@code
 *       device.count}（1-100）
 * </ul>
 *
 * <p>迁移链（首次加载自动执行，失败仅告警不阻塞）：程序目录旧配置 → 新配置目录；单文件配置 → 设备/设置双文件拆分。
 *
 * <p>写入语义：同目录临时文件 + 原子改名，任一步失败不破坏既有文件。线程安全：静态无状态，可并发调用。
 */
public final class ConfigService {

  private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

  /** 软件设置文件名（相对 wol.config.dir） */
  private static final String SETTINGS_FILE_NAME = "settings.properties";

  /** settings.properties 键：连发次数（String 十进制，1-100） */
  private static final String KEY_COUNT = "device.count";

  /** settings.properties 键：主题标识（dark|light） */
  private static final String KEY_THEME = "ui.theme";

  /** 设备键前缀：device.N.<属性>，N 从 1 起 */
  private static final String DEVICE_KEY_PREFIX = "device.";

  /** 设备属性后缀（拼接在 device.N 之后） */
  private static final String KEY_NAME = ".name";

  private static final String KEY_MAC = ".mac";
  private static final String KEY_BROADCAST = ".broadcast";
  private static final String KEY_PORT = ".port";

  /** SRV 模式开关键（true/false，缺失视为 false） */
  private static final String KEY_SRV_ENABLED = ".srvEnabled";

  /** SRV 记录名键（字符串，如 _wol._udp.example.com） */
  private static final String KEY_SRV_NAME = ".srvName";

  /** 旧单设备格式键（v1.0 及更早） */
  private static final String LEGACY_MAC = "device.mac";

  private static final String LEGACY_BROADCAST = "device.broadcast";
  private static final String LEGACY_PORT = "device.port";

  /** 设备键识别正则：组 1 为编号，组 2 为属性名 */
  private static final Pattern DEVICE_INDEX_PATTERN =
      Pattern.compile("^device\\.(\\d+)\\.(name|mac|broadcast|port|srvEnabled|srvName)$");

  /** 配置目录覆盖属性：-Dwol.config.dir=<目录> */
  private static final String CONFIG_DIR_PROPERTY = "wol.config.dir";

  private ConfigService() {}

  /**
   * 加载设备列表；文件缺失时依次尝试旧位置迁移与默认文件兜底。
   *
   * @return 设备配置；文件不存在或读取失败时收敛为「含一台默认设备的配置」，调用方永远得到非空列表
   */
  public static DeviceConfig load() {
    DeviceConfig config = new DeviceConfig();
    Path file = getConfigPath();

    if (!Files.exists(file)) {
      migrateFromLegacyLocation(file);
      if (!Files.exists(file)) {
        createDefaultFile(file);
      }
    }

    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      config.setDevices(parseDevices(props));
    } catch (IOException e) {
      // 读取失败只告警，收敛为默认配置，不让界面启动失败
      log.error("读取配置失败，使用默认值: {}", file, e);
    }

    if (config.getDevices().isEmpty()) {
      config.addDevice(new Device());
    }
    return config;
  }

  /**
   * 原子写入设备配置。
   *
   * @param config 待持久化配置；列表下标与文件编号 device.1..N 一一对应
   * @throws IOException 临时文件写入或改名失败时；失败不破坏既有文件
   */
  public static void save(DeviceConfig config) throws IOException {
    writeAtomically(getConfigPath(), writer -> writeDevices(writer, config));
    log.info("设备配置已保存: {} ({} 台设备)", getConfigPath(), config.getDevices().size());
  }

  /**
   * 加载软件设置；文件缺失时尝试从旧版单文件配置拆分。
   *
   * @return 设置对象；非法键值回退默认（次数 {@link AppConfig#DEFAULT_SEND_COUNT}、主题 {@link
   *     AppConfig#DEFAULT_THEME}）， theme 恒为 dark|light，sendCount 恒在 1-100
   */
  public static AppSettings loadSettings() {
    AppSettings settings = new AppSettings();
    Path file = getSettingsPath();

    if (!Files.exists(file)) {
      migrateSettingsFromLegacyFile(file);
    }

    if (Files.exists(file)) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(file)) {
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        settings.setSendCount(
            parseRange(
                props.getProperty(KEY_COUNT),
                AppConfig.DEFAULT_SEND_COUNT,
                AppConfig.SEND_COUNT_MIN,
                AppConfig.SEND_COUNT_MAX,
                "发送次数"));
        settings.setTheme(props.getProperty(KEY_THEME));
      } catch (IOException e) {
        log.error("读取设置失败，使用默认值: {}", file, e);
      }
    }
    return settings;
  }

  /**
   * 原子写入软件设置。
   *
   * @param settings 待持久化设置
   * @throws IOException 写入或改名失败时；失败不破坏既有文件
   */
  public static void saveSettings(AppSettings settings) throws IOException {
    writeAtomically(getSettingsPath(), writer -> writeSettings(writer, settings));
    log.info("软件设置已保存: {}", getSettingsPath());
  }

  /** 内容写入回调：向已打开的 Writer 输出文件内容 */
  @FunctionalInterface
  private interface ContentWriter {
    void write(Writer writer) throws IOException;
  }

  /**
   * 原子写入：先写同目录 {@code <file>.tmp}，再改名覆盖目标。
   *
   * <p>文件系统不支持原子改名时（部分网络盘）退化为普通替换；父目录不存在时自动创建。
   *
   * @param file 目标文件
   * @param content 内容写入回调
   * @throws IOException 写入或改名失败时
   */
  private static void writeAtomically(Path file, ContentWriter content) throws IOException {
    Files.createDirectories(file.getParent());

    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp);
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
      content.write(writer);
    }
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * 写出设备列表（UTF-8 properties 风格）。
   *
   * <p>数据契约：键 {@code device.N.name|mac|broadcast|port|srvEnabled|srvName}（N 从 1 连续编号，与列表下标对应）； port
   * 为十进制字符串，srvEnabled 为 true/false。值仅转义反斜杠——'=' 出现在值中是安全的（Properties 按首个 '=' 切分键值），换行由单行输入框排除。
   *
   * @throws IOException 写入失败时
   */
  private static void writeDevices(Writer writer, DeviceConfig config) throws IOException {
    String timestamp =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    writer.write("# WOL 设备配置（自动生成，UTF-8）\n");
    writer.write("# " + timestamp + "\n\n");

    List<Device> devices = config.getDevices();
    for (int i = 0; i < devices.size(); i++) {
      Device d = devices.get(i);
      int index = i + 1;
      writer.write("# ---- 设备 " + index + " ----\n");
      writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_NAME, d.getName());
      writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_MAC, d.getMacAddress());
      writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_BROADCAST, d.getBroadcastAddress());
      writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_PORT, String.valueOf(d.getPort()));
      writeEntry(
          writer, DEVICE_KEY_PREFIX + index + KEY_SRV_ENABLED, String.valueOf(d.isSrvEnabled()));
      writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_SRV_NAME, d.getSrvName());
      writer.write("\n");
    }
  }

  /**
   * 写出软件设置：device.count 与 ui.theme 两键。
   *
   * @throws IOException 写入失败时
   */
  private static void writeSettings(Writer writer, AppSettings settings) throws IOException {
    String timestamp =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    writer.write("# WOL 软件设置（自动生成，UTF-8）\n");
    writer.write("# " + timestamp + "\n\n");
    writeEntry(writer, KEY_COUNT, String.valueOf(settings.getSendCount()));
    writeEntry(writer, KEY_THEME, settings.getTheme());
  }

  /**
   * 写出单行键值对。
   *
   * @param key 属性键
   * @param value 属性值；须非 null（模型层已把 null 归一化为空串）
   * @throws IOException 写入失败时
   */
  private static void writeEntry(Writer writer, String key, String value) throws IOException {
    writer.write(key + "=" + value.replace("\\", "\\\\") + "\n");
  }

  /**
   * 解析配置目录：优先系统属性 {@code wol.config.dir}，否则 {@code ~/.wol}。
   *
   * @return 绝对路径；目录由后续写入操作按需创建
   */
  public static Path getConfigDir() {
    String override = System.getProperty(CONFIG_DIR_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Paths.get(override).toAbsolutePath();
    }
    return Paths.get(System.getProperty("user.home", "."), ".wol").toAbsolutePath();
  }

  /**
   * @return 设备配置文件完整路径（{@code <configDir>/device.properties}）
   */
  public static Path getConfigPath() {
    return getConfigDir().resolve(AppConfig.CONFIG_FILE_NAME);
  }

  /**
   * @return 软件设置文件完整路径（{@code <configDir>/settings.properties}）
   */
  public static Path getSettingsPath() {
    return getConfigDir().resolve(SETTINGS_FILE_NAME);
  }

  /**
   * 将旧版本（程序目录）的 device.properties 拷贝到新配置目录。
   *
   * <p>触发条件：新目录文件不存在，且程序目录（JAR/classes 所在目录）存在旧配置文件时；仅拷贝不删除旧文件，回滚安全。
   *
   * <p>副作用：文件拷贝（I/O）；失败仅告警，不阻断启动。
   */
  private static void migrateFromLegacyLocation(Path target) {
    try {
      // 程序目录 = 类所在代码源（JAR 文件或 classes 目录）的父目录
      URL location = ConfigService.class.getProtectionDomain().getCodeSource().getLocation();
      if (location == null) {
        return;
      }
      Path base = Paths.get(location.toURI());
      Path legacyDir = base.toFile().isDirectory() ? base : base.getParent();
      if (legacyDir == null) {
        return;
      }
      Path legacyFile = legacyDir.resolve(AppConfig.CONFIG_FILE_NAME);
      if (!Files.isRegularFile(legacyFile)) {
        return;
      }
      Files.createDirectories(target.getParent());
      Files.copy(legacyFile, target, StandardCopyOption.REPLACE_EXISTING);
      log.info("已将旧位置的配置文件迁移到新目录: {} -> {}", legacyFile, target);
    } catch (IOException | URISyntaxException | SecurityException e) {
      log.warn("旧配置迁移失败: {}", e.toString());
    }
  }

  /**
   * 从旧版单文件配置（device.properties 内嵌设置键）拆分出 settings.properties。
   *
   * <p>触发条件：设置文件不存在且设备文件含 {@code ui.theme} 或 {@code device.count} 键时；设备文件中的设备键不受影响。
   *
   * <p>副作用：写盘设置文件（I/O）；失败仅告警。
   */
  private static void migrateSettingsFromLegacyFile(Path settingsFile) {
    Path legacyDeviceFile = getConfigPath();
    if (!Files.isRegularFile(legacyDeviceFile)) {
      return;
    }
    try {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(legacyDeviceFile)) {
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
      String theme = props.getProperty(KEY_THEME);
      String count = props.getProperty(KEY_COUNT);
      if (theme == null && count == null) {
        return;
      }
      AppSettings settings = new AppSettings();
      settings.setTheme(theme);
      settings.setSendCount(
          parseRange(
              count,
              AppConfig.DEFAULT_SEND_COUNT,
              AppConfig.SEND_COUNT_MIN,
              AppConfig.SEND_COUNT_MAX,
              "发送次数"));
      saveSettings(settings);
      log.info("已从旧版单文件配置拆分出软件设置: {} -> {}", legacyDeviceFile, settingsFile);
    } catch (IOException | SecurityException e) {
      log.warn("旧设置拆分迁移失败: {}", e.toString());
    }
  }

  /**
   * 首次启动兜底：生成含一台默认设备的配置文件。
   *
   * <p>副作用：写盘（I/O）；失败仅告警，本次运行使用内存默认值。
   */
  private static void createDefaultFile(Path file) {
    DeviceConfig defaults = new DeviceConfig();
    defaults.addDevice(new Device());
    try {
      save(defaults);
    } catch (IOException e) {
      log.warn("默认配置文件创建失败，本次将使用内置默认值: {}", file, e);
    }
  }

  /**
   * 解析属性键值对为设备列表。
   *
   * <p>数据契约（Properties 的键 → 值类型）：{@code device.N.name|mac|broadcast|srvName} → String；{@code
   * device.N.port} → String 十进制端口（非法回退默认 9）；{@code device.N.srvEnabled} → String true/false（非法回退
   * false）。 键缺失的属性保留模型默认值；编号乱序时按升序收敛为连续列表。
   *
   * @return 按编号升序的设备列表；无任何设备键且无旧格式键时为空列表
   */
  private static List<Device> parseDevices(Properties props) {
    // TreeMap 保证编号升序输出
    Map<Integer, Device> byIndex = new TreeMap<>();
    for (String key : props.stringPropertyNames()) {
      Matcher m = DEVICE_INDEX_PATTERN.matcher(key);
      if (!m.matches()) {
        continue;
      }
      int index = Integer.parseInt(m.group(1));
      String attr = m.group(2);
      Device d = byIndex.computeIfAbsent(index, k -> new Device());
      switch (attr) {
        case "name" -> d.setName(props.getProperty(key));
        case "mac" -> d.setMacAddress(props.getProperty(key));
        case "broadcast" -> d.setBroadcastAddress(props.getProperty(key));
        case "port" ->
            d.setPort(
                parseRange(
                    props.getProperty(key),
                    AppConfig.DEFAULT_WOL_PORT,
                    AppConfig.PORT_MIN,
                    AppConfig.PORT_MAX,
                    "端口"));
        case "srvEnabled" -> d.setSrvEnabled(Boolean.parseBoolean(props.getProperty(key)));
        case "srvName" -> d.setSrvName(props.getProperty(key));
      }
    }

    // 无 device.N.* 键时兼容旧单设备格式（device.mac|broadcast|port）
    if (byIndex.isEmpty()) {
      String legacyMac = props.getProperty(LEGACY_MAC);
      if (legacyMac != null
          || props.containsKey(LEGACY_BROADCAST)
          || props.containsKey(LEGACY_PORT)) {
        Device legacy = new Device();
        legacy.setMacAddress(props.getProperty(LEGACY_MAC, ""));
        legacy.setBroadcastAddress(
            props.getProperty(LEGACY_BROADCAST, AppConfig.DEFAULT_BROADCAST));
        legacy.setPort(
            parseRange(
                props.getProperty(LEGACY_PORT),
                AppConfig.DEFAULT_WOL_PORT,
                AppConfig.PORT_MIN,
                AppConfig.PORT_MAX,
                "端口"));
        byIndex.put(1, legacy);
        log.info("检测到旧版单设备配置，已迁移为 device.1.*");
      }
    }
    return new ArrayList<>(byIndex.values());
  }

  /**
   * 解析整数属性：空白、非数字或越界一律回退默认值并记录告警。
   *
   * @param raw 属性原始字符串，可为 null
   * @param defaultValue 非法时的回退值
   * @param min 合法下界（含）
   * @param max 合法上界（含）
   * @param label 告警日志中的字段名
   * @return 合法解析值或默认值
   */
  private static int parseRange(String raw, int defaultValue, int min, int max, String label) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      if (value >= min && value <= max) {
        return value;
      }
    } catch (NumberFormatException ignored) {
    }
    log.warn("配置中的{}非法（{}），使用默认值 {}", label, raw, defaultValue);
    return defaultValue;
  }
}
