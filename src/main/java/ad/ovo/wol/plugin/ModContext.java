/*
 * WOL 唤醒工具 - 插件运行上下文。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件运行上下文：向 {@link Mod} 暴露运行环境（配置目录与专属日志）。
 *
 * <p>当前仅提供最小能力集，后续按需扩展（如注册界面扩展点、访问发送服务等）。
 */
public final class ModContext {

  private final String modId;
  private final Path configDir;
  private final Logger logger;

  ModContext(String modId, Path configDir) {
    this.modId = modId;
    this.configDir = configDir;
    this.logger = LoggerFactory.getLogger("ad.ovo.wol.plugin." + modId);
  }

  /** @return 插件唯一标识（与 {@link Mod#id()} 一致） */
  public String getModId() {
    return modId;
  }

  /** @return 应用配置目录（{@code ~/.wol}，插件可在此下写自有配置） */
  public Path getConfigDir() {
    return configDir;
  }

  /** @return 该插件专属 SLF4J 日志器（logger 名为 {@code ad.ovo.wol.plugin.<modId>}） */
  public Logger getLogger() {
    return logger;
  }
}
