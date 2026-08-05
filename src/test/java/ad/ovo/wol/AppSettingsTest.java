package ad.ovo.wol;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.model.AppSettings;
import ad.ovo.wol.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigService} 软件设置测试：主题/次数持久化与旧版单文件拆分迁移。
 *
 * <p>配置目录经系统属性 {@code wol.config.dir} 隔离到临时目录。
 */
class AppSettingsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateConfigDir() {
        System.setProperty("wol.config.dir", tempDir.resolve("config").toString());
    }

    @Test
    void 主题偏好持久化往返一致() throws IOException {
        AppSettings settings = ConfigService.loadSettings();
        settings.setTheme(AppConfig.THEME_LIGHT);
        ConfigService.saveSettings(settings);

        AppSettings reloaded = ConfigService.loadSettings();
        assertEquals(AppConfig.THEME_LIGHT, reloaded.getTheme());
        assertEquals(AppConfig.DEFAULT_SEND_COUNT, reloaded.getSendCount());
    }

    @Test
    void 连发次数持久化往返一致() throws IOException {
        AppSettings settings = ConfigService.loadSettings();
        settings.setSendCount(3);
        ConfigService.saveSettings(settings);

        AppSettings reloaded = ConfigService.loadSettings();
        assertEquals(3, reloaded.getSendCount());
    }

    @Test
    void 非法次数与主题回退默认值() throws IOException {
        Path file = ConfigService.getSettingsPath();
        Files.createDirectories(file.getParent());
        String broken = "device.count=abc\nui.theme=purple\n";
        Files.write(file, broken.getBytes(StandardCharsets.UTF_8));

        AppSettings loaded = ConfigService.loadSettings();
        assertEquals(AppConfig.DEFAULT_SEND_COUNT, loaded.getSendCount());
        assertEquals(AppConfig.DEFAULT_THEME, loaded.getTheme());
    }

    @Test
    void 旧版单文件配置自动拆分出软件设置() throws IOException {
        Path deviceFile = ConfigService.getConfigPath();
        Files.createDirectories(deviceFile.getParent());
        String legacy = "device.1.name=书房电脑\n"
                + "device.1.mac=00:1A:2B:3C:4D:5E\n"
                + "device.1.broadcast=10.0.0.255\n"
                + "device.1.port=9\n"
                + "device.count=3\n"
                + "ui.theme=light\n";
        Files.write(deviceFile, legacy.getBytes(StandardCharsets.UTF_8));

        AppSettings loaded = ConfigService.loadSettings();
        assertEquals(3, loaded.getSendCount());
        assertEquals(AppConfig.THEME_LIGHT, loaded.getTheme());
        assertTrue(Files.exists(ConfigService.getSettingsPath()), "拆分后应生成 settings.properties");
    }

    @Test
    void 设备文件无设置键时设置取默认值() throws IOException {
        Path deviceFile = ConfigService.getConfigPath();
        Files.createDirectories(deviceFile.getParent());
        String deviceOnly = "device.1.mac=00:1A:2B:3C:4D:5E\n";
        Files.write(deviceFile, deviceOnly.getBytes(StandardCharsets.UTF_8));

        AppSettings loaded = ConfigService.loadSettings();
        assertEquals(AppConfig.DEFAULT_SEND_COUNT, loaded.getSendCount());
        assertEquals(AppConfig.DEFAULT_THEME, loaded.getTheme());
    }
}
