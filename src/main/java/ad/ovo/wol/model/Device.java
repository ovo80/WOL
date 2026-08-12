/*
 * WOL 唤醒工具 - 设备模型。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.model;

import ad.ovo.wol.common.config.AppConfig;

/**
 * 设备模型：唤醒目标（设备名/MAC/广播地址/端口，可选 SRV 模式）。
 *
 * <p>持久化键值格式见 {@link ad.ovo.wol.service.ConfigService}（device.N.*）。
 *
 * <p>字段约定：String 字段在 setter 中 trim，null 归一化为空串，因此 getter 返回值恒不为 null；port 默认 {@link
 * AppConfig#DEFAULT_WOL_PORT}；srvEnabled 默认 false（普通模式），srvName 默认空串。
 */
public class Device {

  private String name = "";
  private String macAddress = "";
  private String broadcastAddress = AppConfig.DEFAULT_BROADCAST;
  private int port = AppConfig.DEFAULT_WOL_PORT;

  /** SRV 模式开关：开启后端口由 SRV 记录解析提供，广播字段改为填写 SRV 记录名 */
  private boolean srvEnabled = false;

  /** SRV 记录名（SRV 模式下使用），如 _wol._udp.example.com */
  private String srvName = "";

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

  public boolean isSrvEnabled() {
    return srvEnabled;
  }

  public void setSrvEnabled(boolean srvEnabled) {
    this.srvEnabled = srvEnabled;
  }

  public String getSrvName() {
    return srvName;
  }

  public void setSrvName(String srvName) {
    this.srvName = srvName == null ? "" : srvName.trim();
  }
}
