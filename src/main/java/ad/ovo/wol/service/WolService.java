/*
 * WOL 唤醒工具 - WOL 发送服务接口。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.service;

import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.common.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.util.SrvUtil;

/**
 * WOL 发送服务：魔术包发送的业务编排契约。
 *
 * <p>异常契约：实现类对外只抛 {@link WolException}；参数校验失败与网络异常统一转译，调用方只需处理一种异常类型。
 *
 * <p>副作用：UDP 网络发送（I/O）；SRV 模式额外触发 DNS 查询（I/O）；阻塞调用——连发 N 次耗时约为 N × 100ms（见 {@link
 * AppConfig#SEND_INTERVAL_MS}），调用方应置于后台线程。
 *
 * <p>线程安全：实现类无实例状态，可多线程共享。
 */
public interface WolService {

  /**
   * 按设备配置发送连发魔术包；设备开启 SRV 模式时改走 SRV 解析链路。
   *
   * @param device 目标设备；普通模式要求 mac 合法、broadcast 可解析、port 在 1-65535；SRV 模式要求 srvName 可解析
   * @param count 连发次数，取值 {@link AppConfig#SEND_COUNT_MIN} 至 {@link AppConfig#SEND_COUNT_MAX}
   * @throws WolException 参数校验失败（消息含具体原因）或网络发送失败/中断时
   */
  void sendWakeUp(Device device, int count) throws WolException;

  /**
   * 以显式参数发送连发魔术包（Device 重载的实现主体）。
   *
   * @param mac MAC 地址，":" 或 "-" 分隔的 6 组十六进制，大小写不限
   * @param broadcast 广播地址：IPv4（如 10.0.0.255）、IPv6 或主机名；不允许携带协议前缀（http:// 等）
   * @param port 目标端口，1-65535
   * @param count 连发次数，1-100
   * @throws WolException 校验失败（消息含具体原因）或网络发送失败/中断时；中断路径会恢复线程中断标志
   */
  void sendWakeUp(String mac, String broadcast, int port, int count) throws WolException;

  /**
   * SRV 模式发送：解析 SRV 记录后向解析出的地址单播连发魔术包。
   *
   * <p>链路：MAC/次数校验 → {@link SrvUtil#resolve(String)} 查 SRV（网络 I/O）→ 目标主机名解析 → 端口校验 → 单播发送。 SRV 记录的
   * target 通常为内网穿透服务域名，解析出的地址即穿透节点，端口为穿透节点上映射的 WOL 端口。
   *
   * @param mac MAC 地址（格式同 {@link #sendWakeUp(String, String, int, int)}）
   * @param srvName SRV 记录名或域名（自动补 {@code _wol._udp.} 前缀，见 {@link SrvUtil#normalizeQuery(String)}）
   * @param count 连发次数，1-100
   * @return 解析目标展示文本 {@code IP:端口}（供界面回显，如 1.2.3.4:9000）
   * @throws WolException SRV 记录缺失/查询失败、目标解析失败、参数非法或网络发送失败/中断时；中断路径会恢复线程中断标志
   */
  String sendWakeUpViaSrv(String mac, String srvName, int count) throws WolException;
}
