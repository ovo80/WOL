/*
 * WOL 唤醒工具 - 主题模型。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

/**
 * 主题：一条可应用到界面的配色方案（唯一 id、展示名与 CSS 地址）。
 *
 * <p>来源：内置主题（打包在应用内）或外部主题 jar（放入 {@code resources} 目录，见 {@link ThemeManager}）。
 */
public final class Theme {

  private final String id;
  private final String name;
  private final String cssUrl;
  private final boolean builtin;

  Theme(String id, String name, String cssUrl, boolean builtin) {
    this.id = id;
    this.name = name;
    this.cssUrl = cssUrl;
    this.builtin = builtin;
  }

  /** @return 主题唯一标识（持久化用，如 {@code dark}） */
  public String getId() {
    return id;
  }

  /** @return 主题展示名（如「默认深色」） */
  public String getName() {
    return name;
  }

  /** @return CSS 样式表地址（供 {@code scene.getStylesheets().setAll(...)} 使用） */
  public String getCssUrl() {
    return cssUrl;
  }

  /** @return true 表示应用内置主题，false 表示来自外部 jar */
  public boolean isBuiltin() {
    return builtin;
  }

  @Override
  public String toString() {
    return name;
  }
}
