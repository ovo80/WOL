/*
 * WOL 唤醒工具 - 插件管理器测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ad.ovo.wol.plugin.PluginManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PluginManager} 测试：目录扫描、ServiceLoader 发现、启用/禁用生命周期与持久化偏好应用。
 *
 * <p>端到端场景：把含 {@code META-INF/services} 注册文件与实现类的 jar 放入临时 mods 目录，验证「丢 jar 就加载」的核心机制。测试用
 * {@code TestMod} 类与主工程同包编译（classpath 内），但经 URLClassLoader 从 jar 重新加载以验证真实类加载路径。
 */
class PluginManagerTest {

  @TempDir Path tempDir;

  private Path configDir;

  @BeforeEach
  void setUp() {
    configDir = tempDir.resolve("config");
    System.setProperty("wol.config.dir", configDir.toString());
  }

  @Test
  void 空目录扫描无插件() throws IOException {
    Files.createDirectories(configDir.resolve("mods"));
    PluginManager manager = new PluginManager(configDir);
    manager.scan();
    assertTrue(manager.getMods().isEmpty());
  }

  @Test
  void 目录不存在时扫描无插件() {
    PluginManager manager = new PluginManager(configDir);
    manager.scan();
    assertTrue(manager.getMods().isEmpty());
  }

  @Test
  void 从jar加载插件并启用禁用() throws IOException {
    Path modsDir = configDir.resolve("mods");
    Files.createDirectories(modsDir);
    writeModJar(modsDir.resolve("test-mod.jar"));

    PluginManager manager = new PluginManager(configDir);
    manager.scan();

    assertEquals(1, manager.getMods().size(), "应发现 1 个插件");
    String id = manager.getMods().get(0).id();
    assertEquals("com.example.test", id);
    assertEquals("测试插件", manager.getMods().get(0).name());
    assertFalse(manager.isEnabled(id), "初始应未启用");

    // 启用 → onEnable 被调用
    manager.setEnabled(id, true);
    assertTrue(manager.isEnabled(id));
    assertTrue(TestMod.lastEnabled, "启用应触发 onEnable");

    // 禁用 → onDisable 被调用
    manager.setEnabled(id, false);
    assertFalse(manager.isEnabled(id));
    assertTrue(TestMod.lastDisabled, "禁用应触发 onDisable");

    manager.close();
  }

  @Test
  void 重复启用不重复触发回调() throws IOException {
    Path modsDir = configDir.resolve("mods");
    Files.createDirectories(modsDir);
    writeModJar(modsDir.resolve("test-mod.jar"));

    PluginManager manager = new PluginManager(configDir);
    manager.scan();
    String id = manager.getMods().get(0).id();

    TestMod.enableCount = 0;
    manager.setEnabled(id, true);
    manager.setEnabled(id, true);
    assertEquals(1, TestMod.enableCount, "重复启用不应重复触发 onEnable");
    manager.close();
  }

  @Test
  void 发送模式按id查找且仅启用时可见() throws IOException {
    Path modsDir = configDir.resolve("mods");
    Files.createDirectories(modsDir);
    writeModJar(modsDir.resolve("test-mod.jar"));

    PluginManager manager = new PluginManager(configDir);
    manager.scan();
    String id = manager.getMods().get(0).id();

    // 未启用：找不到发送模式
    assertTrue(manager.findSendMode("test-mode") == null, "未启用时不应返回发送模式");
    assertTrue(manager.getSendModes().isEmpty());

    // 启用后：可通过 mode id 找到发送模式
    manager.setEnabled(id, true);
    assertTrue(manager.findSendMode("test-mode") != null, "启用后应能找到发送模式");
    assertEquals(1, manager.getSendModes().size());
    assertEquals("test-mode", manager.getSendModes().get(0).id());

    // 禁用后再次不可见
    manager.setEnabled(id, false);
    assertTrue(manager.findSendMode("test-mode") == null, "禁用后不应返回发送模式");
    manager.close();
  }

  /** 把 TestMod/TestSendMode 编译产物与 SPI 注册文件打包进 jar（实现类字节从 classpath 读取）。 */
  private void writeModJar(Path jarPath) throws IOException {
    try (OutputStream out = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(out)) {
      jar.putNextEntry(new JarEntry("META-INF/services/ad.ovo.wol.plugin.Mod"));
      jar.write("ad.ovo.wol.TestMod\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      jar.closeEntry();

      for (String classResource : new String[] {"/ad/ovo/wol/TestMod.class", "/ad/ovo/wol/TestSendMode.class"}) {
        try (var in = PluginManagerTest.class.getResourceAsStream(classResource)) {
          if (in == null) {
            throw new IOException("测试实现类未编译: " + classResource);
          }
          jar.putNextEntry(new JarEntry(classResource.substring(1)));
          in.transferTo(jar);
          jar.closeEntry();
        }
      }
    }
  }
}
