/*
 * WOL 唤醒工具 - 发送模式扩展点。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

import ad.ovo.wol.common.exception.WolException;

/**
 * 发送模式扩展点：插件通过实现本接口（并经 {@link Mod#sendMode()} 暴露）为设备提供「普通广播」之外的发送方式。
 *
 * <p>契约：{@link #id()} 为持久化到设备 {@code mode} 字段的唯一标识；{@link #resolve(String)} 把模式专属数据解析为目标地址/端口，核心只负责
 * 构造魔术包与连发，不做模式语义理解。表单语义（label/prompt/是否用端口框）由本接口提供，供主界面动态渲染。
 *
 * <p>异常契约：{@link #resolve(String)} 校验失败或解析失败抛 {@link WolException}（消息面向用户）；实现应为无状态，可多线程共享。
 */
public interface SendMode {

  /** @return 模式唯一标识（如 {@code custom}），持久化到设备的 mode 字段 */
  String id();

  /** @return 模式展示名（如「自定义模式」） */
  String name();

  /** @return 一句话描述（如「内网穿透自动解析端口」） */
  String description();

  /** @return 选中该模式后，广播字段的 label（如「自定义地址」） */
  String broadcastLabel();

  /** @return 选中该模式后，广播字段的 promptText */
  String broadcastPrompt();

  /** @return 选中该模式后，端口字段的 label（如「解析目标」） */
  String portLabel();

  /** @return 选中该模式后，端口字段的 promptText */
  String portPrompt();

  /** @return false 表示该模式不使用端口输入（端口框禁用），true 表示仍需要 */
  boolean usesPortField();

  /**
   * 解析模式专属数据为目标地址与端口。
   *
   * @param modeValue 模式专属数据（由插件定义语义）
   * @return 解析出的目标（地址 + 端口 + 回显文本）
   * @throws WolException 数据为空、格式非法或解析失败时；消息含具体原因
   */
  Target resolve(String modeValue) throws WolException;
}
