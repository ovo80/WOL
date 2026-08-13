/*
 * WOL 唤醒工具 - 主题发现与管理。
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
 * 主题管理器：聚合内置主题与外部主题 jar，供设置窗口展示与选择。
 *
 * <p>内置主题：{@code dark}（默认深色）与 {@code light}（默认浅色），CSS 打包在应用 classpath。
 *
 * <p>外部主题 jar 格式：放入 {@code <configDir>/resources} 的 {@code *.jar}，其根目录须含 {@code wol-theme.properties}，键 {@code
 * id}（必填）、{@code name}（必填）、{@code css}（可选，jar 内 CSS 相对路径，默认 {@code theme.css}）。CSS 经 {@code
 * jar:<fileUrl>!/<css>} 形式引用，由 JavaFX 直接读取。
 *
 * <p>失败语义：单个主题 jar 解析失败（缺描述文件、缺 id/name、CSS 条目不存在）只告警跳过。
 */
public final class ThemeManager {

  private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

  private static final String DESCRIPTOR_ENTRY = "wol-theme.properties";
  private static final String DEFAULT_CSS_ENTRY = "theme.css";

  private final Path resourcesDir;
  private final Map<String, Theme> themes = new LinkedHashMap<>();

  /** @param configDir 应用配置目录（{@code ~/.wol}），resources 目录在其下 */
  public ThemeManager(Path configDir) {
    this.resourcesDir = configDir.resolve(AppConfig.RESOURCES_DIR_NAME);
  }

  /**
   * 扫描并聚合主题：先内置后外部（外部按名称排序）。重复 id 时外部主题被忽略（内置优先）。
   *
   * <p>副作用：I/O 读取 resources 目录与主题 jar；重复调用会重建列表（覆盖旧结果）。
   */
  public void scan() {
    themes.clear();
    registerBuiltin();
    registerExternal();
  }

  /** @return 全部可用主题（先内置后外部，不可修改） */
  public List<Theme> getThemes() {
    return Collections.unmodifiableList(new ArrayList<>(themes.values()));
  }

  /**
   * 按 id 查找主题；未知 id 回退内置深色主题（保证界面始终有可用的样式表）。
   *
   * @param id 主题 id，可为 null
   * @return 匹配的主题，或内置深色主题
   */
  public Theme resolve(String id) {
    if (id != null && themes.containsKey(id)) {
      return themes.get(id);
    }
    log.warn("未知主题 id「{}」，回退默认深色主题", id);
    return themes.get(AppConfig.DEFAULT_THEME);
  }

  private void registerBuiltin() {
    themes.put(
        AppConfig.THEME_DARK,
        new Theme(
            AppConfig.THEME_DARK,
            "默认深色",
            toClasspathUrl("/ad/ovo/wol/css/theme-dark.css"),
            true));
    themes.put(
        AppConfig.THEME_LIGHT,
        new Theme(
            AppConfig.THEME_LIGHT,
            "默认浅色",
            toClasspathUrl("/ad/ovo/wol/css/theme-light.css"),
            true));
  }

  private void registerExternal() {
    List<Path> jars = listJars();
    if (jars.isEmpty()) {
      return;
    }
    List<Theme> external = new ArrayList<>();
    for (Path jar : jars) {
      Theme theme = parseThemeJar(jar);
      if (theme != null) {
        external.add(theme);
      }
    }
    external.sort(Comparator.comparing(Theme::getName));
    for (Theme theme : external) {
      if (themes.putIfAbsent(theme.getId(), theme) != null) {
        log.warn("忽略重复主题 id「{}」（与内置或已加载主题冲突）", theme.getId());
      }
    }
  }

  private Theme parseThemeJar(Path jar) {
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      JarEntry descriptor = jarFile.getJarEntry(DESCRIPTOR_ENTRY);
      if (descriptor == null) {
        log.warn("忽略主题 jar（缺 {}）: {}", DESCRIPTOR_ENTRY, jar.getFileName());
        return null;
      }
      Properties props = new Properties();
      try (InputStream in = jarFile.getInputStream(descriptor)) {
        props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
      }
      String id = props.getProperty("id");
      String name = props.getProperty("name");
      if (id == null || id.isBlank() || name == null || name.isBlank()) {
        log.warn("忽略主题 jar（id/name 缺失）: {}", jar.getFileName());
        return null;
      }
      String cssEntry = props.getProperty("css", DEFAULT_CSS_ENTRY);
      if (jarFile.getJarEntry(cssEntry) == null) {
        log.warn("忽略主题 jar（CSS 条目不存在: {}）: {}", cssEntry, jar.getFileName());
        return null;
      }
      String cssUrl = "jar:" + jar.toUri().toURL() + "!/" + cssEntry;
      return new Theme(id, name, cssUrl, false);
    } catch (IOException e) {
      log.warn("解析主题 jar 失败: {} ({})", jar.getFileName(), e.toString());
      return null;
    }
  }

  private List<Path> listJars() {
    if (!Files.isDirectory(resourcesDir)) {
      return Collections.emptyList();
    }
    try (var stream = Files.list(resourcesDir)) {
      return stream
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (IOException e) {
      log.warn("读取 resources 目录失败: {} ({})", resourcesDir, e.toString());
      return Collections.emptyList();
    }
  }

  private String toClasspathUrl(String resource) {
    var url = ThemeManager.class.getResource(resource);
    return url == null ? "" : url.toExternalForm();
  }
}
