/*
 * WOL 唤醒工具 - 语言发现与管理。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

import ad.ovo.wol.common.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语言管理器：聚合内置语言与外部语言 jar，供设置窗口展示与选择。
 *
 * <p>内置语言：{@code zh-CN}（简体中文）。
 *
 * <p>外部语言 jar 格式：放入 {@code <configDir>/i18n} 的 {@code *.jar}，其根目录须含 {@code wol-language.properties}，键 {@code
 * code}（必填，如 {@code en}）、{@code name}（必填，如 {@code English}）。当前仅做发现与选择持久化，文案翻译能力后续接入。
 *
 * <p>失败语义：单个语言 jar 解析失败（缺描述文件、缺 code/name）只告警跳过。
 */
public final class LanguageManager {

  private static final Logger log = LoggerFactory.getLogger(LanguageManager.class);

  private static final String DESCRIPTOR_ENTRY = "wol-language.properties";

  private final Path i18nDir;
  private final Map<String, Language> languages = new LinkedHashMap<>();

  /** @param configDir 应用配置目录（{@code ~/.wol}），i18n 目录在其下 */
  public LanguageManager(Path configDir) {
    this.i18nDir = configDir.resolve(AppConfig.I18N_DIR_NAME);
  }

  /**
   * 扫描并聚合语言：先内置后外部（外部按名称排序）。重复 code 时外部语言被忽略（内置优先）。
   *
   * <p>副作用：I/O 读取 i18n 目录与语言 jar；重复调用会重建列表（覆盖旧结果）。
   */
  public void scan() {
    languages.clear();
    languages.put(
        AppConfig.DEFAULT_LANGUAGE,
        new Language(AppConfig.DEFAULT_LANGUAGE, "简体中文", true));
    registerExternal();
  }

  /** @return 全部可用语言（先内置后外部，不可修改） */
  public List<Language> getLanguages() {
    return Collections.unmodifiableList(new ArrayList<>(languages.values()));
  }

  /**
   * 按代码查找语言；未知代码回退内置语言（保证始终有可选语言）。
   *
   * @param code 语言代码，可为 null
   * @return 匹配的语言，或内置简体中文
   */
  public Language resolve(String code) {
    if (code != null && languages.containsKey(code)) {
      return languages.get(code);
    }
    return languages.get(AppConfig.DEFAULT_LANGUAGE);
  }

  private void registerExternal() {
    if (!Files.isDirectory(i18nDir)) {
      return;
    }
    List<Language> external = new ArrayList<>();
    try (var stream = Files.list(i18nDir)) {
      for (Path jar :
          stream
              .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
              .filter(Files::isRegularFile)
              .sorted()
              .toList()) {
        Language language = parseLanguageJar(jar);
        if (language != null) {
          external.add(language);
        }
      }
    } catch (IOException e) {
      log.warn("读取 i18n 目录失败: {} ({})", i18nDir, e.toString());
      return;
    }
    external.sort(Comparator.comparing(Language::getName));
    for (Language language : external) {
      if (languages.putIfAbsent(language.getCode(), language) != null) {
        log.warn("忽略重复语言 code「{}」（与内置或已加载语言冲突）", language.getCode());
      }
    }
  }

  private Language parseLanguageJar(Path jar) {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      JarEntry descriptor = jarFile.getJarEntry(DESCRIPTOR_ENTRY);
      if (descriptor == null) {
        log.warn("忽略语言 jar（缺 {}）: {}", DESCRIPTOR_ENTRY, jar.getFileName());
        return null;
      }
      Properties props = new Properties();
      try (InputStream in = jarFile.getInputStream(descriptor)) {
        props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
      }
      String code = props.getProperty("code");
      String name = props.getProperty("name");
      if (code == null || code.isBlank() || name == null || name.isBlank()) {
        log.warn("忽略语言 jar（code/name 缺失）: {}", jar.getFileName());
        return null;
      }
      return new Language(code, name, false);
    } catch (IOException e) {
      log.warn("解析语言 jar 失败: {} ({})", jar.getFileName(), e.toString());
      return null;
    }
  }
}
