/*
 * WOL 唤醒工具 - 主题管理器测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ad.ovo.wol.plugin.Theme;
import ad.ovo.wol.plugin.ThemeManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ThemeManager} 测试：内置主题、外部主题 jar 发现、id 解析与回退。 */
class ThemeManagerTest {

  @TempDir Path tempDir;

  private Path configDir;

  @BeforeEach
  void setUp() {
    configDir = tempDir.resolve("config");
    System.setProperty("wol.config.dir", configDir.toString());
  }

  @Test
  void 内置主题包含默认深色与浅色() {
    ThemeManager manager = new ThemeManager(configDir);
    manager.scan();

    List<Theme> themes = manager.getThemes();
    assertEquals(2, themes.size());
    assertEquals("dark", themes.get(0).getId());
    assertEquals("默认深色", themes.get(0).getName());
    assertEquals("light", themes.get(1).getId());
    assertEquals("默认浅色", themes.get(1).getName());
    assertTrue(themes.get(0).isBuiltin());
  }

  @Test
  void 未知主题id回退默认深色() {
    ThemeManager manager = new ThemeManager(configDir);
    manager.scan();
    assertEquals("dark", manager.resolve("nonexistent").getId());
    assertEquals("dark", manager.resolve(null).getId());
  }

  @Test
  void 从jar发现外部主题() throws IOException {
    Path resourcesDir = configDir.resolve("resources");
    Files.createDirectories(resourcesDir);
    writeThemeJar(resourcesDir.resolve("ocean.jar"), "ocean", "海洋蓝", "theme.css");

    ThemeManager manager = new ThemeManager(configDir);
    manager.scan();

    assertEquals(3, manager.getThemes().size());
    Theme ocean = manager.resolve("ocean");
    assertEquals("ocean", ocean.getId());
    assertEquals("海洋蓝", ocean.getName());
    assertFalse(ocean.isBuiltin());
    assertTrue(ocean.getCssUrl().startsWith("jar:"), "外部主题 CSS 应为 jar: 地址");
  }

  @Test
  void 缺描述文件的jar被忽略() throws IOException {
    Path resourcesDir = configDir.resolve("resources");
    Files.createDirectories(resourcesDir);
    try (OutputStream out = Files.newOutputStream(resourcesDir.resolve("broken.jar"));
        JarOutputStream jar = new JarOutputStream(out)) {
      jar.putNextEntry(new JarEntry("theme.css"));
      jar.write("body{}".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }

    ThemeManager manager = new ThemeManager(configDir);
    manager.scan();
    assertEquals(2, manager.getThemes().size(), "无描述文件的 jar 应被忽略");
  }

  private void writeThemeJar(Path jarPath, String id, String name, String cssEntry)
      throws IOException {
    try (OutputStream out = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(out)) {
      String descriptor =
          "id=" + id + "\nname=" + name + "\ncss=" + cssEntry + "\n";
      jar.putNextEntry(new JarEntry("wol-theme.properties"));
      jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();

      jar.putNextEntry(new JarEntry(cssEntry));
      jar.write(".settings-root { -fx-background-color: #000; }".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
  }
}
