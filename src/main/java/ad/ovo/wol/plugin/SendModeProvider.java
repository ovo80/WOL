/*
 * WOL 唤醒工具 - WOL 业务扩展点：发送模式提供者。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

/**
 * WOL 业务扩展点：宿主侧定义，由插件（实现 {@code ad.ovo.modloader.Mod} 的类）一并实现本接口以提供发送模式。
 *
 * <p>运行时分发：核心遍历已启用插件，{@code instanceof SendModeProvider} 时取 {@link #sendMode()}（见 {@code
 * MainController#refreshModeOptions}）。实现类同时实现两个接口不冲突——本接口与 {@code Mod} 无同签名方法。
 */
public interface SendModeProvider {

  /**
   * 该插件提供的发送模式（可选能力）：返回 null 表示不扩展发送方式。
   *
   * @return 发送模式实现，或 null
   */
  default SendMode sendMode() {
    return null;
  }
}
