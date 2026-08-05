package ad.ovo.wol.service;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.util.WolUtil;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务层：WOL 发送编排——参数校验、异常转译与日志记录。
 *
 * <p>异常契约：对外只抛 {@link WolException}；底层校验异常 （{@link IllegalArgumentException}）与网络异常（{@link
 * IOException}、 {@link InterruptedException}）均在本类转译，调用方只需处理一种异常类型。
 *
 * <p>副作用：UDP 网络发送（I/O）；阻塞调用——连发 N 次耗时约为 N × 100ms（见 {@link AppConfig#SEND_INTERVAL_MS}），调用方应置于后台线程。
 *
 * <p>线程安全：无实例字段，可多线程共享。
 */
public class WolService {

  private static final Logger log = LoggerFactory.getLogger(WolService.class);

  /**
   * 按设备配置发送连发魔术包。
   *
   * @param device 目标设备；mac 需为合法 MAC，broadcast 需可解析， port 需在 1-65535 之间
   * @param count 连发次数，取值 {@link AppConfig#SEND_COUNT_MIN} 至 {@link AppConfig#SEND_COUNT_MAX}
   * @throws WolException 参数校验失败（消息含具体原因）或网络发送失败/中断时
   */
  public void sendWakeUp(Device device, int count) throws WolException {
    sendWakeUp(device.getMacAddress(), device.getBroadcastAddress(), device.getPort(), count);
  }

  /**
   * 以显式参数发送连发魔术包（Device 重载的实现主体）。
   *
   * @param mac MAC 地址，":" 或 "-" 分隔的 6 组十六进制，大小写不限
   * @param broadcast 广播地址：IPv4（如 10.0.0.255）、IPv6 或主机名； 不允许携带协议前缀（http:// 等）
   * @param port 目标端口，1-65535
   * @param count 连发次数，1-100
   * @throws WolException 校验失败（消息含具体原因）或网络发送失败/中断时； 中断路径会恢复线程中断标志
   */
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
      for (int i = 1; i <= count; i++) {
        try {
          WolUtil.sendPacket(socket, packet, address, port);
          // 除最后一次外，包间间隔 100ms 防丢包
          if (i < count) {
            Thread.sleep(AppConfig.SEND_INTERVAL_MS);
          }
        } catch (IOException e) {
          // 任一次失败即终止整轮发送，不再重试
          log.error(
              "唤醒请求发送失败: mac={}, broadcast={}, port={}, 第 {}/{} 次",
              mac,
              broadcast,
              port,
              i,
              count,
              e);
          throw new WolException("网络发送失败（第 " + i + "/" + count + " 次）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
          // 恢复中断标志，供上层线程池感知取消
          Thread.currentThread().interrupt();
          throw new WolException("发送被中断（第 " + i + "/" + count + " 次）", e);
        }
      }
    } catch (IOException e) {
      log.error("UDP Socket 创建失败: broadcast={}, port={}", broadcast, port, e);
      throw new WolException("网络发送失败：" + e.getMessage(), e);
    }
    log.info("唤醒请求完成: mac={}, broadcast={}, port={}, count={}", mac, broadcast, port, count);
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
   * WolUtil#resolveAddress(String)}）； 纯数字与点按 IPv4 逐段校验（4 段、每段 0-255）；其余按主机名规则
   * （字母或数字开头，后续允许字母/数字/点/下划线/连字符）。
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
