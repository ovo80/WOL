/*
 * WOL 唤醒工具 - WOL 发送服务实现。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.service.impl;

import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.common.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.plugin.SendMode;
import ad.ovo.wol.plugin.Target;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.util.WolUtil;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link WolService} 默认实现：UDP 单播/广播发送（无实例状态，可多线程共享）。 */
public class WolServiceImpl implements WolService {

  private static final Logger log = LoggerFactory.getLogger(WolServiceImpl.class);

  @Override
  public String sendWakeUp(Device device, int count, SendMode sendMode) throws WolException {
    if (device.hasCustomMode()) {
      if (sendMode == null) {
        throw new WolException("发送模式「" + device.getMode() + "」对应的插件未启用或不存在");
      }
      return sendViaMode(device, count, sendMode);
    }
    sendWakeUp(device.getMacAddress(), device.getBroadcastAddress(), device.getPort(), count);
    return null;
  }

  @Override
  public void sendWakeUp(String mac, String broadcast, int port, int count) throws WolException {

    // 校验与地址解析先行完成：避免 Socket 已创建后才暴露参数非法
    final byte[] packet;
    final InetAddress address;
    try {
      packet = WolUtil.buildMagicPacket(WolUtil.parseMac(mac));
      WolUtil.validatePort(port);
      validateCount(count);
      validateBroadcast(broadcast);
      address = WolUtil.resolveAddress(broadcast);
    } catch (IllegalArgumentException e) {
      throw new WolException(e.getMessage(), e);
    }

    try (DatagramSocket socket = WolUtil.createBroadcastSocket()) {
      sendLoop(socket, packet, address, port, count, broadcast);
    } catch (IOException e) {
      log.error("UDP Socket 创建失败: broadcast={}, port={}", broadcast, port, e);
      throw new WolException("网络发送失败：" + e.getMessage(), e);
    }
    log.info("唤醒请求完成: mac={}, broadcast={}, port={}, count={}", mac, broadcast, port, count);
  }

  /** 自定义模式发送：委托 SendMode 解析目标，核心负责构造魔术包与连发。 */
  private String sendViaMode(Device device, int count, SendMode sendMode) throws WolException {
    final byte[] packet;
    final Target target;
    try {
      packet = WolUtil.buildMagicPacket(WolUtil.parseMac(device.getMacAddress()));
      validateCount(count);
      target = sendMode.resolve(device.getModeValue());
      WolUtil.validatePort(target.getPort());
    } catch (IllegalArgumentException e) {
      throw new WolException(e.getMessage(), e);
    }

    try (DatagramSocket socket = WolUtil.createBroadcastSocket()) {
      sendLoop(socket, packet, target.getAddress(), target.getPort(), count, target.getDisplay());
    } catch (IOException e) {
      log.error("UDP Socket 创建失败: mode={}, target={}", sendMode.id(), target.getDisplay(), e);
      throw new WolException("网络发送失败：" + e.getMessage(), e);
    }

    String display = target.getDisplay();
    log.info(
        "自定义模式唤醒请求完成: mac={}, mode={}, target={}, count={}",
        device.getMacAddress(),
        sendMode.id(),
        display,
        count);
    return display;
  }

  /**
   * 发送循环：向目标地址连发 N 个魔术包，包间间隔 {@link AppConfig#SEND_INTERVAL_MS}。
   *
   * <p>失败语义：任一次发送失败即终止整轮（不重试）；中断时恢复线程中断标志后抛出，供上层线程池感知取消。
   *
   * @param socket 已配置的发送 Socket（由调用方创建并负责关闭）
   * @param packet 魔术包字节
   * @param address 目标地址（广播或单播）
   * @param port 目标端口
   * @param count 连发次数
   * @param targetLabel 日志用目标描述（广播地址或模式回显文本）
   * @throws IOException 网络发送失败时
   * @throws WolException 发送被中断时（含恢复中断标志的副作用）
   */
  private void sendLoop(
      DatagramSocket socket,
      byte[] packet,
      InetAddress address,
      int port,
      int count,
      String targetLabel)
      throws IOException, WolException {
    for (int i = 1; i <= count; i++) {
      try {
        WolUtil.sendPacket(socket, packet, address, port);
        // 除最后一次外，包间间隔 100ms 防丢包
        if (i < count) {
          Thread.sleep(AppConfig.SEND_INTERVAL_MS);
        }
      } catch (IOException e) {
        // 任一次失败即终止整轮发送，不再重试
        log.error("唤醒请求发送失败: target={}, port={}, 第 {}/{} 次", targetLabel, port, i, count, e);
        throw new WolException("网络发送失败（第 " + i + "/" + count + " 次）：" + e.getMessage(), e);
      } catch (InterruptedException e) {
        // 恢复中断标志，供上层线程池感知取消
        Thread.currentThread().interrupt();
        throw new WolException("发送被中断（第 " + i + "/" + count + " 次）", e);
      }
    }
  }

  /**
   * 校验连发次数范围。
   *
   * @throws IllegalArgumentException 次数不在 {@link AppConfig#SEND_COUNT_MIN} 至 {@link
   *     AppConfig#SEND_COUNT_MAX} 区间时
   */
  private void validateCount(int count) throws IllegalArgumentException {
    if (count < AppConfig.SEND_COUNT_MIN || count > AppConfig.SEND_COUNT_MAX) {
      throw new IllegalArgumentException(
          "发送次数必须在 "
              + AppConfig.SEND_COUNT_MIN
              + "-"
              + AppConfig.SEND_COUNT_MAX
              + " 之间，当前为 "
              + count);
    }
  }

  /**
   * 校验广播地址格式，判定顺序：协议前缀 → IPv6 → IPv4 → 主机名。
   *
   * <p>分支规则：含 {@code "://"} 判定为误带协议前缀；含 {@code ":"} 按 IPv6 字符集校验（语义校验交由 {@link
   * WolUtil#resolveAddress(String)}）；纯数字与点按 IPv4 逐段校验（4 段、每段 0-255）；其余按主机名规则（字母或数字开头，
   * 后续允许字母/数字/点/下划线/连字符）。
   *
   * @throws IllegalArgumentException 任一规则不满足时，消息含具体原因
   */
  private void validateBroadcast(String broadcast) throws IllegalArgumentException {
    if (broadcast == null || broadcast.isBlank()) {
      throw new IllegalArgumentException("广播地址不能为空");
    }
    String trimmed = broadcast.trim();

    // 误带协议前缀是高频输入错误，单独给出明确提示
    if (trimmed.contains("://")) {
      throw new IllegalArgumentException(
          "广播地址不需要协议前缀（如 http://），" + "请直接输入 IP 地址或主机名，例如 10.0.0.255");
    }

    // IPv6 形态：仅校验字符集，地址语义交由 InetAddress 解析
    if (trimmed.contains(":")) {
      if (!trimmed.matches("[0-9A-Fa-f:.]+")) {
        throw new IllegalArgumentException("IPv6 广播地址包含非法字符：「" + trimmed + "」");
      }
      return;
    }

    // IPv4 形态：逐段校验段数、非空、纯数字与 0-255 范围
    if (trimmed.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')) {
      String[] parts = trimmed.split("\\.", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException(
            "广播地址必须为 IPv4 格式（如 10.0.0.255），当前为 " + parts.length + " 段");
      }
      for (String part : parts) {
        if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
          throw new IllegalArgumentException("广播地址包含非数字部分：「" + trimmed + "」");
        }
        int value;
        try {
          value = Integer.parseInt(part);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("广播地址段数值超出范围：「" + part + "」");
        }
        if (value < 0 || value > 255) {
          throw new IllegalArgumentException("广播地址每段范围 0-255：「" + part + "」");
        }
      }
      return;
    }

    // 其余按主机名处理：首字符字母/数字，整体 1-253 字符
    if (!trimmed.matches("[A-Za-z0-9]([A-Za-z0-9._-]{0,252})")) {
      throw new IllegalArgumentException(
          "广播地址格式不合法：「" + trimmed + "」" + "（应为 IP 地址或主机名，例如 10.0.0.255 / broadcast.local）");
    }
  }
}
