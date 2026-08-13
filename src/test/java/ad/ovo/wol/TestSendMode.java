/*
 * WOL 唤醒工具 - 测试用发送模式（供 FxmlLoadTest 端到端加载）。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import ad.ovo.wol.plugin.SendMode;
import ad.ovo.wol.plugin.Target;

/** 测试用发送模式：id 为 {@code test-mode}，resolve 返回 null（仅验证查找链路，不实际发送）。 */
public class TestSendMode implements SendMode {

  @Override
  public String id() {
    return "test-mode";
  }

  @Override
  public String name() {
    return "测试模式";
  }

  @Override
  public String description() {
    return "单元测试用发送模式";
  }

  @Override
  public String broadcastLabel() {
    return "测试数据";
  }

  @Override
  public String broadcastPrompt() {
    return "任意值";
  }

  @Override
  public String portLabel() {
    return "解析目标";
  }

  @Override
  public String portPrompt() {
    return "解析后回显";
  }

  @Override
  public boolean usesPortField() {
    return false;
  }

  @Override
  public Target resolve(String modeValue) {
    return null;
  }
}
