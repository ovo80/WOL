package ad.ovo.wol.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * 工具类：构造并发送 Wake-on-LAN（WOL）魔术包。
 *
 * <p><b>本类不包含任何 GUI / 业务编排代码</b>，只做纯网络底层操作，可独立测试与复用。</p>
 *
 * <p>WOL 魔术包格式：6 字节 {@code 0xFF} + 目标 MAC 地址重复 16 次，共 102 字节。</p>
 *
 * <p>异常约定：参数问题抛 {@link IllegalArgumentException}，网络问题抛 {@link IOException}，
 * 由上层 {@code WolService} 统一转译为业务异常。</p>
 */
public final class WolUtil {

    private static final Logger log = LoggerFactory.getLogger(WolUtil.class);

    /** 魔术包固定前缀长度（6 字节 0xFF） */
    private static final int PREFIX_LENGTH = 6;

    /** MAC 地址重复次数 */
    private static final int MAC_REPEAT = 16;

    /** MAC 地址字节数 */
    private static final int MAC_LENGTH = 6;

    private WolUtil() {
        // 工具类禁止实例化
    }

    /**
     * 校验并解析 MAC 地址。
     * <p>支持格式：{@code XX:XX:XX:XX:XX:XX} 或 {@code XX-XX-XX-XX-XX-XX}（大小写不限）。</p>
     *
     * @param mac 用户输入的 MAC 地址
     * @return 6 字节的 MAC 地址字节数组
     * @throws IllegalArgumentException 格式不合法（组数、长度或非十六进制字符）
     */
    public static byte[] parseMac(String mac) throws IllegalArgumentException {
        if (mac == null || mac.isBlank()) {
            throw new IllegalArgumentException("MAC 地址不能为空");
        }
        String trimmed = mac.trim();

        // 统一分隔符后按 ':' 分组；limit=-1 保留尾部空串，使 "AA:BB:...:FF:" 这类尾随分隔符被组数校验拦截
        String normalized = trimmed.replace('-', ':');
        String[] groups = normalized.split(":", -1);

        if (groups.length != MAC_LENGTH) {
            throw new IllegalArgumentException("MAC 地址必须为 " + MAC_LENGTH + " 组，当前为 "
                    + groups.length + " 组（正确格式：XX:XX:XX:XX:XX:XX）");
        }

        byte[] result = new byte[MAC_LENGTH];
        for (int i = 0; i < MAC_LENGTH; i++) {
            String group = groups[i];
            if (group.length() != 2) {
                throw new IllegalArgumentException("MAC 地址第 " + (i + 1)
                        + " 组长度错误：「" + group + "」应为 2 位十六进制");
            }
            try {
                result[i] = (byte) Integer.parseInt(group, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("MAC 地址第 " + (i + 1)
                        + " 组「" + group + "」不是合法十六进制字符");
            }
        }
        return result;
    }

    /**
     * 构造 WOL 魔术包：6 字节 {@code 0xFF} + MAC 重复 16 次（共 102 字节）。
     *
     * @param macBytes 6 字节 MAC 地址
     * @return 102 字节的魔术包
     */
    public static byte[] buildMagicPacket(byte[] macBytes) {
        if (macBytes == null || macBytes.length != MAC_LENGTH) {
            throw new IllegalArgumentException("MAC 地址字节数组长度必须为 " + MAC_LENGTH);
        }
        byte[] packet = new byte[PREFIX_LENGTH + MAC_REPEAT * MAC_LENGTH];
        // 前 6 字节全为 0xFF（Java byte 的 0xFF 即 -1）
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            packet[i] = (byte) 0xFF;
        }
        // MAC 重复 16 次
        for (int i = 0; i < MAC_REPEAT; i++) {
            System.arraycopy(macBytes, 0, packet, PREFIX_LENGTH + i * MAC_LENGTH, MAC_LENGTH);
        }
        return packet;
    }

    /**
     * 通过 UDP 广播发送 WOL 魔术包到指定广播地址与端口。
     *
     * @param mac       目标主机 MAC 地址（支持 XX:XX:XX:XX:XX:XX / 连字符分隔，大小写不限）
     * @param broadcast 子网广播地址，如 10.0.0.255、192.168.1.255
     * @param port      目标 UDP 端口（1-65535，WOL 标准为 9）
     * @throws IllegalArgumentException MAC / 广播地址 / 端口不合法（发送前校验）
     * @throws IOException             网络发送失败（Socket 创建、绑定或发包异常）
     */
    public static void sendWOL(String mac, String broadcast, int port)
            throws IllegalArgumentException, IOException {
        // 先做纯校验/准备（不产生资源），最后才建 Socket：非法输入零资源开销
        byte[] packet = buildMagicPacket(parseMac(mac));
        InetAddress address = resolveAddress(broadcast);
        try (DatagramSocket socket = createBroadcastSocket()) {
            sendPacket(socket, packet, address, port);
        }
    }

    /**
     * 解析广播地址为主机地址（连发场景应只解析一次并复用结果）。
     *
     * @param broadcast IPv4 / IPv6 字面量或主机名
     * @throws IllegalArgumentException 地址为空或无法解析
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
        // 启发式校验（不调用 InetAddress.isBroadcastAddress()：部分 JDK 构建缺失该方法）：
        // IPv4 广播地址主机位全 1（最后一段 255）；IPv6 广播/组播地址以 FF 开头；仅提示不拦截
        byte[] raw = broadcastAddress.getAddress();
        boolean looksLikeBroadcast = (raw.length == 4 && (raw[3] & 0xFF) == 255)
                || (raw.length == 16 && (raw[0] & 0xFF) == 0xFF);
        if (!looksLikeBroadcast && !broadcastAddress.isAnyLocalAddress()) {
            log.warn("目标地址不是标准子网广播地址，可能无法唤醒目标主机: {}", broadcast);
        }
        return broadcastAddress;
    }

    /**
     * 创建启用广播的 UDP Socket（连发场景应复用同一个 Socket，避免反复创建）。
     *
     * @throws IOException Socket 创建或配置失败
     */
    public static DatagramSocket createBroadcastSocket() throws IOException {
        try {
            DatagramSocket socket = new DatagramSocket();
            // 关键：启用广播，否则发送到 255 地址会抛 SocketException(Permission denied)
            socket.setBroadcast(true);
            socket.setSoTimeout(2000);
            return socket;
        } catch (SocketException e) {
            throw new IOException("创建 UDP Socket 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用已创建的 Socket 发送一个魔术包（供连发循环复用）。
     *
     * @throws IOException 发包失败
     */
    public static void sendPacket(DatagramSocket socket, byte[] packet, InetAddress address, int port)
            throws IOException {
        validatePort(port);
        DatagramPacket datagram = new DatagramPacket(packet, packet.length, address, port);
        socket.send(datagram);
        log.info("魔术包已发送 -> {}:{} ({} bytes)", address.getHostAddress(), port, packet.length);
    }

    /** 端口范围校验（1-65535） */
    public static void validatePort(int port) throws IllegalArgumentException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口号必须在 1-65535 之间，当前为 " + port);
        }
    }
}
