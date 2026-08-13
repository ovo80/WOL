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
import ad.ovo.wol.plugin.SendMode;

/**
 * WOL 发送服务：魔术包发送的业务编排契约。
 *
 * <p>异常契约：实现类对外只抛 {@link WolException}；参数校验失败与网络异常统一转译，调用方只需处理一种异常类型。
 *
 * <p>发送模式：设备 {@code mode} 非空时委托 {@link SendMode#resolve} 解析目标，否则按普通广播发送；核心只负责构造魔术包与连发，不感知具体模式语义。
 *
 * <p>副作用：UDP 网络发送（I/O）；自定义模式额外触发其解析逻辑（可能网络 I/O）；阻塞调用——连发 N 次耗时约为 N × 100ms（见 {@link
 * AppConfig#SEND_INTERVAL_MS}），调用方应置于后台线程。
 *
 * <p>线程安全：实现类无实例状态，可多线程共享。
 */
public interface WolService {

  /**
   * 按设备配置发送连发魔术包；设备启用自定义发送模式时委托对应 {@link SendMode} 解析目标。
   *
   * @param device 目标设备；普通模式要求 mac 合法、broadcast 可解析、port 在 1-65535；自定义模式要求对应插件已启用且 modeValue 可解析
   * @param count 连发次数，取值 {@link AppConfig#SEND_COUNT_MIN} 至 {@link AppConfig#SEND_COUNT_MAX}
   * @param sendMode 设备 mode 对应的发送模式（mode 非空时须非 null）；普通模式下忽略
   * @return 自定义模式下的回显文本（如 {@code IP:端口}），普通模式返回 null
   * @throws WolException 参数校验失败（消息含具体原因）、模式未启用或网络发送失败/中断时
   */
  String sendWakeUp(Device device, int count, SendMode sendMode) throws WolException;

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
}
