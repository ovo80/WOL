/*
 * WOL 唤醒工具 - 设备配置持久化测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.service.ConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigService} 设备配置测试：往返读写、文件分离、迁移与原子写入。
 *
 * <p>全部用例通过系统属性 {@code wol.config.dir} 隔离到临时目录（{@code @TempDir}），不触碰真实用户配置。
 */
class DeviceConfigTest {

  @TempDir Path tempDir;

  @BeforeEach
  void isolateConfigDir() {
    System.setProperty("wol.config.dir", tempDir.resolve("config").toString());
  }

  @Test
  void 多设备配置往返读写一致() throws IOException {
    DeviceConfig saved = new DeviceConfig();
    Device pc = makeDevice("00:1A:2B:3C:4D:5E", "10.0.0.255", 9);
    pc.setName("书房电脑");
    Device nas = makeDevice("AA:BB:CC:DD:EE:FF", "192.168.1.255", 7);
    nas.setName("客厅 NAS");
    saved.addDevice(pc);
    saved.addDevice(nas);
    ConfigService.save(saved);

    DeviceConfig loaded = ConfigService.load();
    assertEquals(2, loaded.getDevices().size());
    assertEquals("书房电脑", loaded.getDevices().get(0).getName());
    assertEquals("00:1A:2B:3C:4D:5E", loaded.getDevices().get(0).getMacAddress());
    assertEquals(7, loaded.getDevices().get(1).getPort());
    assertTrue(Files.exists(ConfigService.getConfigPath()), "配置文件应已创建");
  }

  @Test
  void 设备配置与软件设置文件分离() throws IOException {
    DeviceConfig saved = new DeviceConfig();
    saved.addDevice(makeDevice("00:1A:2B:3C:4D:5E", "10.0.0.255", 9));
    ConfigService.save(saved);

    String deviceContent =
        new String(Files.readAllBytes(ConfigService.getConfigPath()), StandardCharsets.UTF_8);
    assertTrue(deviceContent.contains("device.1.mac"), "设备文件应含设备数据");
    assertTrue(!deviceContent.contains("ui.theme"), "设备文件不应含主题设置");
    assertTrue(!deviceContent.contains("device.count"), "设备文件不应含连发次数");
  }

  @Test
  void 保存后无tmp临时文件残留() throws IOException {
    DeviceConfig config = ConfigService.load();
    ConfigService.save(config);
    assertTrue(
        !Files.exists(ConfigService.getConfigPath().resolveSibling("device.properties.tmp")),
        "保存后不应残留 .tmp 临时文件");
  }

  @Test
  void getDevices返回只读视图() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> ConfigService.load().getDevices().add(new Device()));
  }

  @Test
  void 旧版单设备配置自动迁移() throws IOException {
    Path file = ConfigService.getConfigPath();
    Files.createDirectories(file.getParent());
    String legacy =
        "# legacy\n"
            + "device.mac=00:11:22:33:44:55\n"
            + "device.broadcast=192.168.0.255\n"
            + "device.port=9\n";
    Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));

    DeviceConfig loaded = ConfigService.load();
    assertEquals(1, loaded.getDevices().size());
    assertEquals("00:11:22:33:44:55", loaded.getDevices().get(0).getMacAddress());
    assertEquals("192.168.0.255", loaded.getDevices().get(0).getBroadcastAddress());
  }

  @Test
  void 非法端口回退默认值() throws IOException {
    Path file = ConfigService.getConfigPath();
    Files.createDirectories(file.getParent());
    String broken =
        "device.1.name=书房电脑\n"
            + "device.1.mac=00:1A:2B:3C:4D:5E\n"
            + "device.1.broadcast=10.0.0.255\n"
            + "device.1.port=99999\n";
    Files.write(file, broken.getBytes(StandardCharsets.UTF_8));

    DeviceConfig loaded = ConfigService.load();
    assertEquals(9, loaded.getDevices().get(0).getPort());
  }

  @Test
  void 配置缺失时创建默认文件并兜底一台设备() {
    Path configPath = ConfigService.getConfigPath();
    DeviceConfig loaded = ConfigService.load();
    assertTrue(Files.exists(configPath), "配置缺失时应收敛到默认文件");
    assertEquals(1, loaded.getDevices().size());
  }

  @Test
  void 发送模式字段往返读写一致() throws IOException {
    DeviceConfig saved = new DeviceConfig();
    Device pc = makeDevice("00:1A:2B:3C:4D:5E", "10.0.0.255", 9);
    pc.setMode("custom-mode");
    pc.setModeValue("custom-data");
    saved.addDevice(pc);
    ConfigService.save(saved);

    DeviceConfig loaded = ConfigService.load();
    assertEquals(1, loaded.getDevices().size());
    assertEquals("custom-mode", loaded.getDevices().get(0).getMode());
    assertEquals("custom-data", loaded.getDevices().get(0).getModeValue());
  }

  @Test
  void 旧配置缺失发送模式键时回退普通模式() throws IOException {
    Path file = ConfigService.getConfigPath();
    Files.createDirectories(file.getParent());
    String legacy =
        "device.1.name=书房电脑\n"
            + "device.1.mac=00:1A:2B:3C:4D:5E\n"
            + "device.1.broadcast=10.0.0.255\n"
            + "device.1.port=9\n";
    Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));

    DeviceConfig loaded = ConfigService.load();
    Device device = loaded.getDevices().get(0);
    assertEquals("", device.getMode(), "旧配置默认应为普通模式");
    assertEquals("", device.getModeValue());
  }

  @Test
  void 未识别旧键忽略且不迁移() throws IOException {
    // core 不感知任何插件专属旧键：出现时忽略，模式保持默认普通广播
    Path file = ConfigService.getConfigPath();
    Files.createDirectories(file.getParent());
    String legacy =
        "device.1.name=书房电脑\n"
            + "device.1.mac=00:1A:2B:3C:4D:5E\n"
            + "device.1.broadcast=10.0.0.255\n"
            + "device.1.port=9\n"
            + "device.1.legacyCustomKey=true\n";
    Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));

    DeviceConfig loaded = ConfigService.load();
    Device device = loaded.getDevices().get(0);
    assertEquals("", device.getMode(), "core 不识别插件专属旧键，应保持普通模式");
    assertEquals("", device.getModeValue(), "core 不识别插件专属旧键，应保持空模式数据");
  }

  /** 构造测试设备（广播/端口可配，名称默认空）。 */
  private Device makeDevice(String mac, String broadcast, int port) {
    Device d = new Device();
    d.setMacAddress(mac);
    d.setBroadcastAddress(broadcast);
    d.setPort(port);
    return d;
  }
}
