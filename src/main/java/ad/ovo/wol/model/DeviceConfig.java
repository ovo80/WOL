package ad.ovo.wol.model;

import ad.ovo.wol.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DeviceConfig {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfig.class);

    private static final String KEY_COUNT = "device.count";
    private static final String KEY_THEME = "ui.theme";
    private static final String DEVICE_KEY_PREFIX = "device.";
    private static final String KEY_NAME = ".name";
    private static final String KEY_MAC = ".mac";
    private static final String KEY_BROADCAST = ".broadcast";
    private static final String KEY_PORT = ".port";


    private static final String LEGACY_MAC = "device.mac";
    private static final String LEGACY_BROADCAST = "device.broadcast";
    private static final String LEGACY_PORT = "device.port";


    private static final Pattern DEVICE_INDEX_PATTERN = Pattern.compile("^device\\.(\\d+)\\.(name|mac|broadcast|port)$");

    private final List<Device> devices = new ArrayList<>();
    private int sendCount = AppConfig.DEFAULT_SEND_COUNT;
    private String theme = AppConfig.DEFAULT_THEME;


    public static DeviceConfig load() {
        DeviceConfig config = new DeviceConfig();
        Path file = getConfigPath();

        if (!Files.exists(file)) {
            createDefaultFile(file);
        }
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {

                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.sendCount = parseRange(props.getProperty(KEY_COUNT), AppConfig.DEFAULT_SEND_COUNT,
                        AppConfig.SEND_COUNT_MIN, AppConfig.SEND_COUNT_MAX, "发送次数");
                config.theme = parseTheme(props.getProperty(KEY_THEME));
                config.devices.addAll(parseDevices(props));
            } catch (IOException e) {
                log.error("读取配置失败，使用默认值: {}", file, e);
            }
        }

        if (config.devices.isEmpty()) {
            config.devices.add(new Device());
        }
        return config;
    }


    public void save() throws IOException {
        Path file = getConfigPath();
        Files.createDirectories(file.getParent());

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp);
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writeFormatted(writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {

            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("配置已保存: {} ({} 台设备)", file, devices.size());
    }


    private void writeFormatted(Writer writer) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        writer.write("# WOL 工具配置（自动生成，UTF-8）\n");
        writer.write("# " + timestamp + "\n\n");

        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            int index = i + 1;
            writer.write("# ---- 设备 " + index + " ----\n");
            writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_NAME, d.getName());
            writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_MAC, d.getMacAddress());
            writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_BROADCAST, d.getBroadcastAddress());
            writeEntry(writer, DEVICE_KEY_PREFIX + index + KEY_PORT, String.valueOf(d.getPort()));
            writer.write("\n");
        }
        writeEntry(writer, KEY_COUNT, String.valueOf(sendCount));
        writeEntry(writer, KEY_THEME, theme == null ? AppConfig.DEFAULT_THEME : theme);
    }


    private static void writeEntry(Writer writer, String key, String value) throws IOException {
        writer.write(key + "=" + value.replace("\\", "\\\\") + "\n");
    }


    public List<Device> getDevices() {
        return Collections.unmodifiableList(devices);
    }


    public void addDevice(Device device) {
        devices.add(device);
    }


    public void setDevices(List<Device> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
    }

    public int deviceCount() {
        return devices.size();
    }


    public static Path getConfigDir() {
        try {
            URL location = DeviceConfig.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return Paths.get("").toAbsolutePath();
            }
            Path base = Paths.get(location.toURI());

            return base.toFile().isDirectory() ? base : base.getParent();
        } catch (URISyntaxException | SecurityException e) {
            log.warn("无法定位程序目录，回退到当前工作目录", e);
            return Paths.get("").toAbsolutePath();
        }
    }


    public static Path getConfigPath() {
        return getConfigDir().resolve(AppConfig.CONFIG_FILE_NAME);
    }


    private static void createDefaultFile(Path file) {
        DeviceConfig defaults = new DeviceConfig();
        defaults.devices.add(new Device());
        try {
            defaults.save();
        } catch (IOException e) {
            log.warn("默认配置文件创建失败，本次将使用内置默认值: {}", file, e);
        }
    }


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
                default -> {  }
            }
        }

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

    private static String parseTheme(String raw) {
        if (AppConfig.THEME_LIGHT.equalsIgnoreCase(raw) || AppConfig.THEME_DARK.equalsIgnoreCase(raw)) {
            return raw.toLowerCase();
        }
        return AppConfig.DEFAULT_THEME;
    }


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
