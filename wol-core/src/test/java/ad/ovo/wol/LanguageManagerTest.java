/*
 * WOL 唤醒工具 - 语言管理器测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ad.ovo.wol.plugin.Language;
import ad.ovo.wol.plugin.LanguageManager;
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

/** {@link LanguageManager} 测试：内置语言、外部语言 jar 发现、code 解析与回退。 */
class LanguageManagerTest {

  @TempDir Path tempDir;

  private Path configDir;

  @BeforeEach
  void setUp() {
    configDir = tempDir.resolve("config");
    System.setProperty("wol.config.dir", configDir.toString());
  }

  @Test
  void 内置语言包含简体中文() {
    LanguageManager manager = new LanguageManager(configDir);
    manager.scan();

    List<Language> languages = manager.getLanguages();
    assertEquals(1, languages.size());
    assertEquals("zh-CN", languages.get(0).getCode());
    assertEquals("简体中文", languages.get(0).getName());
  }

  @Test
  void 未知语言code回退内置() {
    LanguageManager manager = new LanguageManager(configDir);
    manager.scan();
    assertEquals("zh-CN", manager.resolve("en").getCode());
    assertEquals("zh-CN", manager.resolve(null).getCode());
  }

  @Test
  void 从jar发现外部语言() throws IOException {
    Path i18nDir = configDir.resolve("i18n");
    Files.createDirectories(i18nDir);
    writeLanguageJar(i18nDir.resolve("english.jar"), "en", "English");

    LanguageManager manager = new LanguageManager(configDir);
    manager.scan();

    assertEquals(2, manager.getLanguages().size());
    Language en = manager.resolve("en");
    assertEquals("en", en.getCode());
    assertEquals("English", en.getName());
    assertFalse(en.isBuiltin());
  }

  @Test
  void 缺描述文件的jar被忽略() throws IOException {
    Path i18nDir = configDir.resolve("i18n");
    Files.createDirectories(i18nDir);
    try (OutputStream out = Files.newOutputStream(i18nDir.resolve("broken.jar"));
        JarOutputStream jar = new JarOutputStream(out)) {
      jar.putNextEntry(new JarEntry("messages.properties"));
      jar.write("hello=hi".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }

    LanguageManager manager = new LanguageManager(configDir);
    manager.scan();
    assertEquals(1, manager.getLanguages().size());
  }

  private void writeLanguageJar(Path jarPath, String code, String name) throws IOException {
    try (OutputStream out = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(out)) {
      String descriptor = "code=" + code + "\nname=" + name + "\n";
      jar.putNextEntry(new JarEntry("wol-language.properties"));
      jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
  }
}
