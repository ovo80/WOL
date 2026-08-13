/*
 * WOL 唤醒工具 - 测试用插件实现（供 PluginManagerTest 端到端加载）。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import ad.ovo.wol.plugin.Mod;
import ad.ovo.wol.plugin.ModContext;
import ad.ovo.wol.plugin.SendMode;

/** 测试插件：记录生命周期回调次数，并提供测试用发送模式，供断言验证。 */
public class TestMod implements Mod {

  static boolean lastEnabled;
  static boolean lastDisabled;
  static int enableCount;
  static int disableCount;

  private final SendMode sendMode = new TestSendMode();

  @Override
  public String id() {
    return "com.example.test";
  }

  @Override
  public String name() {
    return "测试插件";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public String description() {
    return "单元测试用插件";
  }

  @Override
  public SendMode sendMode() {
    return sendMode;
  }

  @Override
  public void onEnable(ModContext context) {
    lastEnabled = true;
    lastDisabled = false;
    enableCount++;
  }

  @Override
  public void onDisable() {
    lastDisabled = true;
    lastEnabled = false;
    disableCount++;
  }
}
