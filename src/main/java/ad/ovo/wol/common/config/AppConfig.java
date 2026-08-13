/*
 * WOL 唤醒工具 - 全局常量集中管理。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局常量集中管理：端口/连发次数/主题标识/配置文件名。
 *
 * <p>约定：业务代码一律引用本类常量，禁止散落魔法数值；新增可调参数时优先在此定义边界与默认值。
 */
public final class AppConfig {

  private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

  /** 应用显示名（窗口标题） */
  public static final String APP_NAME = "WOL 唤醒工具";

  /** 应用版本号，从 app.properties 读取；读取失败回退 "dev" */
  public static final String APP_VERSION = loadVersion();

  /** WOL 默认目标端口（协议约定端口 9） */
  public static final int DEFAULT_WOL_PORT = 9;

  /** 端口合法区间 */
  public static final int PORT_MIN = 1;

  public static final int PORT_MAX = 65535;

  /** 连发次数默认值 */
  public static final int DEFAULT_SEND_COUNT = 5;

  /** 连发次数合法区间 */
  public static final int SEND_COUNT_MIN = 1;

  public static final int SEND_COUNT_MAX = 100;

  /** 连发间隔毫秒数（降低丢包概率） */
  public static final long SEND_INTERVAL_MS = 100L;

  /** 默认广播地址（IPv4 子网广播） */
  public static final String DEFAULT_BROADCAST = "10.0.0.255";

  /** 设备配置文件相对名（相对 wol.config.dir 目录） */
  public static final String CONFIG_FILE_NAME = "device.properties";

  /** 主题标识：深色 */
  public static final String THEME_DARK = "dark";

  /** 主题标识：浅色 */
  public static final String THEME_LIGHT = "light";

  /** 默认主题 */
  public static final String DEFAULT_THEME = THEME_DARK;

  /** 默认语言代码（简体中文） */
  public static final String DEFAULT_LANGUAGE = "zh-CN";

  /** 插件目录名（相对配置目录）：放 Mod jar */
  public static final String MODS_DIR_NAME = "mods";

  /** 资源目录名（相对配置目录）：放主题 jar */
  public static final String RESOURCES_DIR_NAME = "resources";

  /** 国际化目录名（相对配置目录）：放语言 jar */
  public static final String I18N_DIR_NAME = "i18n";

  private AppConfig() {}

  /**
   * 从 classpath 资源 {@code /ad/ovo/wol/app.properties} 读取版本号。
   *
   * <p>数据契约：属性键 {@code app.version}；资源缺失、读取失败或值为空时统一回退 {@code "dev"}。
   *
   * @return 版本号，如 {@code "1.1.0"}；异常路径返回 {@code "dev"}
   */
  private static String loadVersion() {
    try (InputStream in = AppConfig.class.getResourceAsStream("/ad/ovo/wol/app.properties")) {
      if (in == null) {
        return "dev";
      }
      Properties props = new Properties();
      props.load(in);
      String version = props.getProperty("app.version", "dev").trim();
      return version.isEmpty() ? "dev" : version;
    } catch (IOException e) {
      log.warn("读取版本信息失败，使用占位版本 dev", e);
      return "dev";
    }
  }
}
