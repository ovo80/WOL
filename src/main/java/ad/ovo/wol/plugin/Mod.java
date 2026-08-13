/*
 * WOL 唤醒工具 - 插件（Mod）服务提供者接口。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

/**
 * 插件 SPI：第三方 jar 提供本接口实现，放入 {@code mods} 目录即可被加载。
 *
 * <p>发现机制：{@link PluginManager} 用 {@link java.util.ServiceLoader} 扫描 mods 目录下所有 jar 中的 {@code
 * META-INF/services/ad.ovo.wol.plugin.Mod} 注册文件，实例化实现类。实现类须有无参构造器。
 *
 * <p>生命周期契约：{@link #onEnable(ModContext)} 在用户启用时调用，{@link #onDisable()} 在禁用时调用；两者均可能被多次调用，
 * 实现须幂等（重复启用不重复注册副作用）。
 */
public interface Mod {

  /** @return 全局唯一标识（建议用域名反写，如 {@code com.example.wol.xxx}），用于持久化启用状态 */
  String id();

  /** @return 展示名（设置窗口「模组」页显示） */
  String name();

  /** @return 版本号（展示用，非约束） */
  String version();

  /** @return 一句话描述（设置窗口「模组」页显示） */
  String description();

  /**
   * 启用回调：在用户勾选启用、或启动时发现已被启用时触发。
   *
   * @param context 插件上下文（配置目录等运行环境信息）
   */
  default void onEnable(ModContext context) {}

  /** 禁用回调：在用户取消启用时触发。 */
  default void onDisable() {}

  /**
   * 该插件提供的发送模式（可选能力）：返回 null 表示不扩展发送方式。
   *
   * <p>核心在设备 {@code mode} 非空时，按 id 查找已启用插件提供的 {@link SendMode} 并委托发送（见 {@code
   * ad.ovo.wol.service.impl.WolServiceImpl}）。
   *
   * @return 发送模式实现，或 null
   */
  default SendMode sendMode() {
    return null;
  }
}
