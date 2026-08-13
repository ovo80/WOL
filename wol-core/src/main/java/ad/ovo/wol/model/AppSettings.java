/*
 * WOL 唤醒工具 - 软件设置模型。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.model;

import ad.ovo.wol.common.config.AppConfig;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 软件设置模型：连发次数、主题、语言与启用插件集合（持久化到 settings.properties）。
 *
 * <p>数据契约：文件键 {@code device.count}（int，1-100）、{@code ui.theme}（主题 id）、{@code ui.language}（语言 code）与 {@code
 * mod.enabled.<id>}（true/false）；非法键值在加载时回退默认（见 {@link
 * ad.ovo.wol.service.ConfigService#loadSettings()}）。
 */
public class AppSettings {

  private int sendCount = AppConfig.DEFAULT_SEND_COUNT;
  private String theme = AppConfig.DEFAULT_THEME;
  private String language = AppConfig.DEFAULT_LANGUAGE;
  private final Set<String> enabledMods = new LinkedHashSet<>();

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
   * 设置主题标识，空白回退默认深色主题。
   *
   * <p>注意：此处只保证非空，不校验 id 是否真实存在（存在性由主题管理器在应用时解析，见 {@code
   * ad.ovo.wol.plugin.ThemeManager#resolve}）。
   *
   * @param theme 主题 id（如 dark / light 或外部主题 id）；null 或空白一律回退 {@link AppConfig#DEFAULT_THEME}
   */
  public void setTheme(String theme) {
    this.theme = theme == null || theme.isBlank() ? AppConfig.DEFAULT_THEME : theme.trim();
  }

  public String getLanguage() {
    return language;
  }

  /**
   * 设置语言代码，空白回退默认语言。
   *
   * @param language 语言 code（如 zh-CN / en）；null 或空白一律回退 {@link AppConfig#DEFAULT_LANGUAGE}
   */
  public void setLanguage(String language) {
    this.language =
        language == null || language.isBlank() ? AppConfig.DEFAULT_LANGUAGE : language.trim();
  }

  /** @return 已启用插件 id 集合（不可修改视图，顺序为启用先后） */
  public Set<String> getEnabledMods() {
    return Collections.unmodifiableSet(enabledMods);
  }

  /**
   * 设置单个插件的启用状态。
   *
   * @param modId 插件 id
   * @param enabled true 加入启用集合，false 移出
   */
  public void setModEnabled(String modId, boolean enabled) {
    if (modId == null || modId.isBlank()) {
      return;
    }
    if (enabled) {
      enabledMods.add(modId);
    } else {
      enabledMods.remove(modId);
    }
  }
}
