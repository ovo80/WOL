package ad.ovo.wol.model;

import ad.ovo.wol.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model 层：应用配置（设备列表 + 全局设置）与持久化。
 *
 * <p>配置文件存放在<b>程序所在目录</b>（JAR 同目录）下的 {@code device.properties}：
 * 打包运行时为可执行 JAR 旁；开发运行时为 {@code target/classes} 下。</p>
 *
 * <p>存储格式（编号多设备 + 全局设置）：</p>
 * <pre>
 * device.count=5            # 全局：每次点击连发次数
 * ui.theme=dark             # 全局：主题
 * device.1.name=书房电脑
 * device.1.mac=00:1A:2B:3C:4D:5E
 * device.1.broadcast=10.0.0.255
 * device.1.port=9
 * device.2.name=客厅 NAS
 * ...
 * </pre>
 *
 * <p>旧版单设备格式（{@code device.mac / device.broadcast / device.port}）自动迁移为
 * {@code device.1.*}，保证向后兼容。</p>
 */
public class DeviceConfig {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfig.class);

    private static final String KEY_COUNT = "device.count";
    private static final String KEY_THEME = "ui.theme";
    private static final String DEVICE_KEY_PREFIX = "device.";
    private static final String KEY_NAME = ".name";
    private static final String KEY_MAC = ".mac";
    private static final String KEY_BROADCAST = ".broadcast";
    private static final String KEY_PORT = ".port";

    /** 旧版单设备键（迁移用） */
    private static final String LEGACY_MAC = "device.mac";
    private static final String LEGACY_BROADCAST = "device.broadcast";
    private static final String LEGACY_PORT = "device.port";

    /** 匹配 device.<数字>.<属性> */
    private static final Pattern DEVICE_INDEX_PATTERN = Pattern.compile("^device\\.(\\d+)\\.(name|mac|broadcast|port)$");

    private final List<Device> devices = new ArrayList<>();
    private int sendCount = AppConfig.DEFAULT_SEND_COUNT;
    private String theme = AppConfig.DEFAULT_THEME;

    /**
     * 读取配置：程序目录加载（不存在则创建默认）；旧单设备格式自动迁移；保证至少一个设备。
     *
     * @return 配置对象，永不返回 null
     */
    public static DeviceConfig load() {
        DeviceConfig config = new DeviceConfig();
        Path file = getConfigPath();

        if (!Files.exists(file)) {
            createDefaultFile(file);
        }
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                config.sendCount = parseRange(props.getProperty(KEY_COUNT), AppConfig.DEFAULT_SEND_COUNT,
                        AppConfig.SEND_COUNT_MIN, AppConfig.SEND_COUNT_MAX, "发送次数");
                config.theme = parseTheme(props.getProperty(KEY_THEME));
                config.devices.addAll(parseDevices(props));
            } catch (IOException e) {
                log.error("读取配置失败，使用默认值: {}", file, e);
            }
        }
        // 保证至少一个设备可用
        if (config.devices.isEmpty()) {
            config.devices.add(new Device());
        }
        return config;
    }

    /**
     * 立即将当前配置写入程序所在目录下的配置文件（目录不存在会自动创建）。
     * <p><b>原子写入</b>：先写临时文件再原子移动，避免写入中断导致配置文件截断损坏。</p>
     *
     * @throws IOException 写入失败时抛出（如目录无写权限），由调用方统一处理
     */
    public void save() throws IOException {
        Path file = getConfigPath();
        Files.createDirectories(file.getParent());

        Properties props = new Properties();
        props.setProperty(KEY_COUNT, String.valueOf(sendCount));
        props.setProperty(KEY_THEME, theme == null ? AppConfig.DEFAULT_THEME : theme);
        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            int index = i + 1;
            props.setProperty(DEVICE_KEY_PREFIX + index + KEY_NAME, d.getName());
            props.setProperty(DEVICE_KEY_PREFIX + index + KEY_MAC, d.getMacAddress());
            props.setProperty(DEVICE_KEY_PREFIX + index + KEY_BROADCAST, d.getBroadcastAddress());
            props.setProperty(DEVICE_KEY_PREFIX + index + KEY_PORT, String.valueOf(d.getPort()));
        }

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "WOL tool config (auto-generated)");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 部分文件系统不支持原子移动，回退普通移动（仍有 tmp 缓冲，损坏窗口远小于直接截断写）
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("配置已保存: {} ({} 台设备)", file, devices.size());
    }

    // ==================== 设备列表操作 ====================

    /**
     * 设备列表的只读视图（防外部直接改内部列表，封装保护）。
     * 增删改请使用 {@link #addDevice} / {@link #setDevices}。
     */
    public List<Device> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    /** 追加一台设备 */
    public void addDevice(Device device) {
        devices.add(device);
    }

    /** 整体替换设备列表（拷贝入参，避免外部引用泄漏进内部状态） */
    public void setDevices(List<Device> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
    }

    public int deviceCount() {
        return devices.size();
    }

    // ==================== 解析与持久化辅助 ====================

    /** 程序所在目录：打包态为可执行 JAR 所在目录，开发态为编译输出目录（如 target/classes） */
    public static Path getConfigDir() {
        try {
            URL location = DeviceConfig.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return Paths.get("").toAbsolutePath();
            }
            Path base = Paths.get(location.toURI());
            // 用 File.isDirectory() 判断（不抛检查异常，兼容不同 JDK 构建对 Files API 的差异）
            return base.toFile().isDirectory() ? base : base.getParent();
        } catch (URISyntaxException | SecurityException e) {
            log.warn("无法定位程序目录，回退到当前工作目录", e);
            return Paths.get("").toAbsolutePath();
        }
    }

    /** 配置文件路径：程序所在目录 / device.properties */
    public static Path getConfigPath() {
        return getConfigDir().resolve(AppConfig.CONFIG_FILE_NAME);
    }

    /** 首次运行：创建默认配置文件（写入失败仅警告，不影响使用默认值） */
    private static void createDefaultFile(Path file) {
        DeviceConfig defaults = new DeviceConfig();
        defaults.devices.add(new Device());
        try {
            defaults.save();
        } catch (IOException e) {
            log.warn("默认配置文件创建失败，本次将使用内置默认值: {}", file, e);
        }
    }

    /** 解析多设备列表，并将旧版单设备格式迁移为 device.1.* */
    private static List<Device> parseDevices(Properties props) {
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
                case "port" -> d.setPort(parseRange(props.getProperty(key), AppConfig.DEFAULT_WOL_PORT,
                        AppConfig.PORT_MIN, AppConfig.PORT_MAX, "端口"));
                default -> { /* ignore */ }
            }
        }
        // 旧版单设备迁移：仅当没有编号设备时，把 device.mac / device.broadcast / device.port 归入 1 号
        if (byIndex.isEmpty()) {
            String legacyMac = props.getProperty(LEGACY_MAC);
            if (legacyMac != null || props.containsKey(LEGACY_BROADCAST) || props.containsKey(LEGACY_PORT)) {
                Device legacy = new Device();
                legacy.setMacAddress(props.getProperty(LEGACY_MAC, ""));
                legacy.setBroadcastAddress(props.getProperty(LEGACY_BROADCAST, AppConfig.DEFAULT_BROADCAST));
                legacy.setPort(parseRange(props.getProperty(LEGACY_PORT), AppConfig.DEFAULT_WOL_PORT,
                        AppConfig.PORT_MIN, AppConfig.PORT_MAX, "端口"));
                byIndex.put(1, legacy);
                log.info("检测到旧版单设备配置，已迁移为 device.1.*");
            }
        }
        return new ArrayList<>(byIndex.values());
    }

    /** 通用范围解析：非法或缺失时回退默认值 */
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
            // fallthrough
        }
        log.warn("配置中的{}非法（{}），使用默认值 {}", label, raw, defaultValue);
        return defaultValue;
    }

    private static String parseTheme(String raw) {
        if (AppConfig.THEME_LIGHT.equalsIgnoreCase(raw) || AppConfig.THEME_DARK.equalsIgnoreCase(raw)) {
            return raw.toLowerCase();
        }
        return AppConfig.DEFAULT_THEME;
    }

    // ---- getters / setters（全局设置） ----

    public int getSendCount() {
        return sendCount;
    }

    public void setSendCount(int sendCount) {
        this.sendCount = sendCount;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = parseTheme(theme);
    }
}
