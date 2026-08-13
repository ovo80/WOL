/*
 * WOL 唤醒工具 - 设备模型。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.model;

import ad.ovo.wol.common.config.AppConfig;

/**
 * 设备模型：唤醒目标（设备名/MAC/广播地址/端口，可选发送模式）。
 *
 * <p>持久化键值格式见 {@link ad.ovo.wol.service.ConfigService}（device.N.*）。
 *
 * <p>字段约定：String 字段在 setter 中 trim，null 归一化为空串，因此 getter 返回值恒不为 null；port 默认 {@link
 * AppConfig#DEFAULT_WOL_PORT}；mode 默认空串（普通广播模式），modeValue 为模式专属数据（由插件定义的语义）。
 */
public class Device {

  private String name = "";
  private String macAddress = "";
  private String broadcastAddress = AppConfig.DEFAULT_BROADCAST;
  private int port = AppConfig.DEFAULT_WOL_PORT;

  /** 发送模式标识：空串 = 普通广播；非空 = 插件提供的 {@link ad.ovo.wol.plugin.SendMode#id()} */
  private String mode = "";

  /** 模式专属数据（由插件定义语义），普通模式下不使用 */
  private String modeValue = "";

  /**
   * 列表展示名：名称为空时回退「未命名设备」。
   *
   * @return 去首尾空白后的名称，或占位文案
   */
  public String displayName() {
    return name == null || name.isBlank() ? "未命名设备" : name.trim();
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name == null ? "" : name.trim();
  }

  public String getMacAddress() {
    return macAddress;
  }

  public void setMacAddress(String macAddress) {
    this.macAddress = macAddress == null ? "" : macAddress.trim();
  }

  public String getBroadcastAddress() {
    return broadcastAddress;
  }

  public void setBroadcastAddress(String broadcastAddress) {
    this.broadcastAddress = broadcastAddress == null ? "" : broadcastAddress.trim();
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  /** @return 发送模式标识（空串 = 普通广播） */
  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode == null ? "" : mode.trim();
  }

  /** @return 模式专属数据（普通模式下为空串） */
  public String getModeValue() {
    return modeValue;
  }

  public void setModeValue(String modeValue) {
    this.modeValue = modeValue == null ? "" : modeValue.trim();
  }

  /** @return true 表示该设备使用插件提供的发送模式（而非普通广播） */
  public boolean hasCustomMode() {
    return !mode.isBlank();
  }
}
