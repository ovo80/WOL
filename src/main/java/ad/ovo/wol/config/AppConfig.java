package ad.ovo.wol.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用级常量集中管理（企业规范：禁止魔法数字散落各处）。
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** 应用名称（窗口标题） */
    public static final String APP_NAME = "局域网 WOL 唤醒工具";

    /**
     * 应用版本（构建期由 Maven filtering 从 pom.xml 注入 app.properties，
     * 单一事实来源；读取失败时回退占位值，不影响启动）。
     */
    public static final String APP_VERSION = loadVersion();

    /** WOL 标准目标端口（UDP 9） */
    public static final int DEFAULT_WOL_PORT = 9;

    /** 端口合法范围 */
    public static final int PORT_MIN = 1;
    public static final int PORT_MAX = 65535;

    /** 每次点击连发的魔术包数量（WOL 惯例：连发多次防丢包） */
    public static final int DEFAULT_SEND_COUNT = 5;

    /** 发送次数合法范围 */
    public static final int SEND_COUNT_MIN = 1;
    public static final int SEND_COUNT_MAX = 100;

    /** 连发间隔（毫秒），避免瞬时网络风暴 */
    public static final long SEND_INTERVAL_MS = 100L;

    /** 默认子网广播地址 */
    public static final String DEFAULT_BROADCAST = "10.0.0.255";

    /** 配置文件名（存放在程序所在目录，即 JAR 同目录） */
    public static final String CONFIG_FILE_NAME = "device.properties";

    /** 主题标识 */
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String DEFAULT_THEME = THEME_DARK;

    private AppConfig() {
        // 常量类禁止实例化
    }

    /** 从过滤后的 app.properties 读取版本号，失败回退 "dev" */
    private static String loadVersion() {
        try (InputStream in = AppConfig.class.getResourceAsStream("/ad/ovo/wol/app.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("app.version", "dev").trim();
            return version.isEmpty() ? "dev" : version;
        } catch (IOException e) {
            log.warn("读取版本信息失败，使用占位版本 dev", e);
            return "dev";
        }
    }
}
