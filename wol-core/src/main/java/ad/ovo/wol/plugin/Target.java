/*
 * WOL 唤醒工具 - 发送目标解析结果。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

import java.net.InetAddress;

/**
 * 发送目标解析结果：由 {@link SendMode#resolve(String)} 返回，供核心发送链路使用。
 *
 * <p>数据契约：{@code address} 为最终收包地址（广播或单播），{@code port} 为 1-65535，{@code display} 为面向用户的回显文本（如 {@code
 * 1.2.3.4:9000}）。
 */
public final class Target {

  private final InetAddress address;
  private final int port;
  private final String display;

  /**
   * @param address 目标地址（非 null）
   * @param port 目标端口，1-65535
   * @param display 回显文本（可空，如 {@code "1.2.3.4:9000"}）
   */
  public Target(InetAddress address, int port, String display) {
    this.address = address;
    this.port = port;
    this.display = display;
  }

  /** @return 目标地址 */
  public InetAddress getAddress() {
    return address;
  }

  /** @return 目标端口 */
  public int getPort() {
    return port;
  }

  /** @return 回显文本（可空） */
  public String getDisplay() {
    return display;
  }
}
