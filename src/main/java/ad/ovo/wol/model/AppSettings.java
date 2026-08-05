package ad.ovo.wol.model;

import ad.ovo.wol.config.AppConfig;

/**
 * 软件设置模型：连发次数与主题（持久化到 settings.properties）。
 *
 * <p>数据契约：文件键 {@code device.count}（int，1-100）与
 * {@code ui.theme}（dark|light）；非法键值在加载时回退默认
 * （见 {@link ad.ovo.wol.service.ConfigService#loadSettings()}）。
 */
public class AppSettings {

    private int sendCount = AppConfig.DEFAULT_SEND_COUNT;
    private String theme = AppConfig.DEFAULT_THEME;

    public int getSendCount() {
        return sendCount;
    }

    public void setSendCount(int sendCount) {
        this.sendCount = sendCount;
    }

    public String getTheme() {
        return theme;
    }

    /**
     * 设置主题标识，非法值回退深色主题。
     *
     * @param theme dark 或 light（大小写不敏感）；null 或其他值一律回退
     *         {@link AppConfig#DEFAULT_THEME}
     */
    public void setTheme(String theme) {
        if (AppConfig.THEME_LIGHT.equalsIgnoreCase(theme) || AppConfig.THEME_DARK.equalsIgnoreCase(theme)) {
            this.theme = theme.toLowerCase();
        } else {
            this.theme = AppConfig.DEFAULT_THEME;
        }
    }
}
