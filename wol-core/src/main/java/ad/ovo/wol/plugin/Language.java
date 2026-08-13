/*
 * WOL 唤醒工具 - 语言模型。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

/**
 * 语言：一个可选的界面语言（唯一代码与展示名）。
 *
 * <p>来源：内置语言（简体中文）或外部语言 jar（放入 {@code i18n} 目录，见 {@link LanguageManager}）。
 */
public final class Language {

  private final String code;
  private final String name;
  private final boolean builtin;

  Language(String code, String name, boolean builtin) {
    this.code = code;
    this.name = name;
    this.builtin = builtin;
  }

  /** @return 语言代码（持久化用，如 {@code zh-CN}） */
  public String getCode() {
    return code;
  }

  /** @return 语言展示名（如「简体中文」） */
  public String getName() {
    return name;
  }

  /** @return true 表示内置语言，false 表示来自外部 jar */
  public boolean isBuiltin() {
    return builtin;
  }

  @Override
  public String toString() {
    return name;
  }
}
