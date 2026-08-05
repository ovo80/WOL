package ad.ovo.wol.util;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 底层网络工具：魔术包构造与 UDP 发送（纯静态，无实例状态）。
 *
 * <p>异常契约：参数错误抛 {@link IllegalArgumentException}（消息面向用户， 可直接展示）；网络失败抛 {@link IOException}（由 Service
 * 层转译）。
 *
 * <p>线程安全：全部为无状态静态方法，可并发调用。
 */
public final class WolUtil {

  private static final Logger log = LoggerFactory.getLogger(WolUtil.class);

  /** 魔术包前缀：6 字节全 0xFF 同步头 */
  private static final int PREFIX_LENGTH = 6;

  /** MAC 地址在包内的重复次数 */
  private static final int MAC_REPEAT = 16;

  /** MAC 地址字节长度 */
  private static final int MAC_LENGTH = 6;

  private WolUtil() {}

  /**
   * 解析 MAC 字符串为 6 字节数组。
   *
   * <p>数据契约：入参支持 ":" 或 "-" 分隔的 6 组两位十六进制 （如 {@code 00:1A:2B:3C:4D:5E}），大小写不限，容忍首尾空白， 分隔符可混用。
   *
   * @param mac MAC 字符串；null 或空白视为非法
   * @return 6 字节数组（字节序与 MAC 原文一致）
   * @throws IllegalArgumentException 组数不为 6、任一组非两位十六进制时； 消息含具体出错位置
   */
  public static byte[] parseMac(String mac) throws IllegalArgumentException {
    if (mac == null || mac.isBlank()) {
      throw new IllegalArgumentException("MAC 地址不能为空");
    }
    String trimmed = mac.trim();

    // 统一 "-" 为 ":" 后按组分隔，避免两套分隔逻辑
    String normalized = trimmed.replace('-', ':');
    String[] groups = normalized.split(":", -1);

    if (groups.length != MAC_LENGTH) {
      throw new IllegalArgumentException(
          "MAC 地址必须为 " + MAC_LENGTH + " 组，当前为 " + groups.length + " 组（正确格式：XX:XX:XX:XX:XX:XX）");
    }

    byte[] result = new byte[MAC_LENGTH];
    for (int i = 0; i < MAC_LENGTH; i++) {
      String group = groups[i];
      if (group.length() != 2) {
        throw new IllegalArgumentException(
            "MAC 地址第 " + (i + 1) + " 组长度错误：「" + group + "」应为 2 位十六进制");
      }
      try {
        result[i] = (byte) Integer.parseInt(group, 16);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("MAC 地址第 " + (i + 1) + " 组「" + group + "」不是合法十六进制字符");
      }
    }
    return result;
  }

  /**
   * 构造魔术包：6 字节 0xFF 前缀 + MAC 重复 16 次，共 102 字节。
   *
   * <p>WOL 协议格式：网卡识别到 6 字节前缀后，读取后续 16 份相同的 MAC， 匹配自身地址即触发唤醒。
   *
   * @param macBytes 恰好 6 字节的 MAC（来自 {@link #parseMac(String)}）
   * @return 102 字节魔术包
   * @throws IllegalArgumentException macBytes 为 null 或长度不为 6
   */
  public static byte[] buildMagicPacket(byte[] macBytes) {
    if (macBytes == null || macBytes.length != MAC_LENGTH) {
      throw new IllegalArgumentException("MAC 地址字节数组长度必须为 " + MAC_LENGTH);
    }
    byte[] packet = new byte[PREFIX_LENGTH + MAC_REPEAT * MAC_LENGTH];

    for (int i = 0; i < PREFIX_LENGTH; i++) {
      packet[i] = (byte) 0xFF;
    }

    for (int i = 0; i < MAC_REPEAT; i++) {
      System.arraycopy(macBytes, 0, packet, PREFIX_LENGTH + i * MAC_LENGTH, MAC_LENGTH);
    }
    return packet;
  }

  /**
   * 解析广播地址，并对「是否形似子网广播」给出预警。
   *
   * <p>判定规则：IPv4 末段为 255，或 IPv6 首字节为 0xFF，视为标准广播； 不满足且非本机任意地址（0.0.0.0/::）时仅记录警告、不阻止发送——
   * 单播唤醒等合法目标不应被误杀。不用 {@code InetAddress.isBroadcastAddress()}：部分 JDK 17 构建缺失该方法 实现，改以字节级判定。
   *
   * @param broadcast IPv4/IPv6 地址或主机名（主机名形态触发 DNS 解析）
   * @return 解析后的地址
   * @throws IllegalArgumentException 入参为 null/空白，或解析失败时； 消息含原始地址
   */
  public static InetAddress resolveAddress(String broadcast) throws IllegalArgumentException {
    if (broadcast == null || broadcast.isBlank()) {
      throw new IllegalArgumentException("广播地址不能为空");
    }
    InetAddress broadcastAddress;
    try {
      broadcastAddress = InetAddress.getByName(broadcast.trim());
    } catch (IOException e) {
      throw new IllegalArgumentException("广播地址「" + broadcast + "」无法解析: " + e.getMessage(), e);
    }

    // IPv4 末段 255 / IPv6 首字节 0xFF 即标准广播；其余仅告警不拦截
    byte[] raw = broadcastAddress.getAddress();
    boolean looksLikeBroadcast =
        (raw.length == 4 && (raw[3] & 0xFF) == 255)
            || (raw.length == 16 && (raw[0] & 0xFF) == 0xFF);
    if (!looksLikeBroadcast && !broadcastAddress.isAnyLocalAddress()) {
      log.warn("目标地址不是标准子网广播地址，可能无法唤醒目标主机: {}", broadcast);
    }
    return broadcastAddress;
  }

  /**
   * 创建广播发送用 UDP Socket。
   *
   * <p>配置：SO_BROADCAST 开启（允许发往广播地址）；SO_TIMEOUT 2000ms 为 防御性设置——当前发送路径不读取数据，仅防极端情况下悬挂。
   *
   * @return 已配置的 Socket；调用方负责关闭（建议 try-with-resources）
   * @throws IOException Socket 创建或配置失败时（SocketException 已转译）
   */
  public static DatagramSocket createBroadcastSocket() throws IOException {
    try {
      DatagramSocket socket = new DatagramSocket();

      socket.setBroadcast(true);
      socket.setSoTimeout(2000);
      return socket;
    } catch (SocketException e) {
      throw new IOException("创建 UDP Socket 失败: " + e.getMessage(), e);
    }
  }

  /**
   * 向目标地址发送单个魔术包。
   *
   * @param socket 发送用 Socket（来自 {@link #createBroadcastSocket()}， 由调用方持有并负责关闭）
   * @param packet 魔术包字节（102 字节，见 {@link #buildMagicPacket(byte[])}）
   * @param address 目标地址（广播或单播）
   * @param port 目标端口，1-65535（越界由 OS 层报错，本方法不校验）
   * @throws IOException 网络发送失败时
   */
  public static void sendPacket(DatagramSocket socket, byte[] packet, InetAddress address, int port)
      throws IOException {
    DatagramPacket datagram = new DatagramPacket(packet, packet.length, address, port);
    socket.send(datagram);
    log.debug("魔术包已发送 -> {}:{} ({} bytes)", address.getHostAddress(), port, packet.length);
  }

  /**
   * 校验端口范围。
   *
   * @throws IllegalArgumentException 端口不在 1-65535 时
   */
  public static void validatePort(int port) throws IllegalArgumentException {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("端口号必须在 1-65535 之间，当前为 " + port);
    }
  }
}
