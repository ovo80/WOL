package ad.ovo.wol.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;


public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);


    public static final String APP_NAME = "局域网 WOL 唤醒工具";


    public static final String APP_VERSION = loadVersion();


    public static final int DEFAULT_WOL_PORT = 9;


    public static final int PORT_MIN = 1;
    public static final int PORT_MAX = 65535;


    public static final int DEFAULT_SEND_COUNT = 5;


    public static final int SEND_COUNT_MIN = 1;
    public static final int SEND_COUNT_MAX = 100;


    public static final long SEND_INTERVAL_MS = 100L;


    public static final String DEFAULT_BROADCAST = "10.0.0.255";


    public static final String CONFIG_FILE_NAME = "device.properties";


    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String DEFAULT_THEME = THEME_DARK;

    private AppConfig() {

    }


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
