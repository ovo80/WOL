package ad.ovo.wol.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;


public final class WolUtil {

    private static final Logger log = LoggerFactory.getLogger(WolUtil.class);


    private static final int PREFIX_LENGTH = 6;


    private static final int MAC_REPEAT = 16;


    private static final int MAC_LENGTH = 6;

    private WolUtil() {

    }


    public static byte[] parseMac(String mac) throws IllegalArgumentException {
        if (mac == null || mac.isBlank()) {
            throw new IllegalArgumentException("MAC 地址不能为空");
        }
        String trimmed = mac.trim();


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


    public static void sendWOL(String mac, String broadcast, int port)
            throws IllegalArgumentException, IOException {

        byte[] packet = buildMagicPacket(parseMac(mac));
        InetAddress address = resolveAddress(broadcast);
        try (DatagramSocket socket = createBroadcastSocket()) {
            sendPacket(socket, packet, address, port);
        }
    }


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


        byte[] raw = broadcastAddress.getAddress();
        boolean looksLikeBroadcast = (raw.length == 4 && (raw[3] & 0xFF) == 255)
                || (raw.length == 16 && (raw[0] & 0xFF) == 0xFF);
        if (!looksLikeBroadcast && !broadcastAddress.isAnyLocalAddress()) {
            log.warn("目标地址不是标准子网广播地址，可能无法唤醒目标主机: {}", broadcast);
        }
        return broadcastAddress;
    }


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


    public static void sendPacket(DatagramSocket socket, byte[] packet, InetAddress address, int port)
            throws IOException {
        validatePort(port);
        DatagramPacket datagram = new DatagramPacket(packet, packet.length, address, port);
        socket.send(datagram);
        log.info("魔术包已发送 -> {}:{} ({} bytes)", address.getHostAddress(), port, packet.length);
    }


    public static void validatePort(int port) throws IllegalArgumentException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口号必须在 1-65535 之间，当前为 " + port);
        }
    }
}
