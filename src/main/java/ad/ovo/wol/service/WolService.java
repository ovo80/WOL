package ad.ovo.wol.service;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.util.WolUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;


public class WolService {

    private static final Logger log = LoggerFactory.getLogger(WolService.class);


    public void sendWakeUp(Device device, int count) throws WolException {
        sendWakeUp(device.getMacAddress(), device.getBroadcastAddress(), device.getPort(), count);
    }


    public void sendWakeUp(String mac, String broadcast, int port, int count) throws WolException {


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
                    if (i < count) {
                        Thread.sleep(AppConfig.SEND_INTERVAL_MS);
                    }
                } catch (IOException e) {
                    log.error("唤醒请求发送失败: mac={}, broadcast={}, port={}, 第 {}/{} 次",
                            mac, broadcast, port, i, count, e);
                    throw new WolException("网络发送失败（第 " + i + "/" + count + " 次）：" + e.getMessage(), e);
                } catch (InterruptedException e) {
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


    public DeviceConfig loadConfig() {
        DeviceConfig config = DeviceConfig.load();
        log.debug("配置加载完成: {}", DeviceConfig.getConfigPath());
        return config;
    }


    public void saveConfig(DeviceConfig config) throws WolException {
        try {
            config.save();
        } catch (IOException e) {
            log.error("配置保存失败: {}", DeviceConfig.getConfigPath(), e);
            throw new WolException("配置保存失败：" + e.getMessage(), e);
        }
    }


    private void validateCount(int count) throws IllegalArgumentException {
        if (count < AppConfig.SEND_COUNT_MIN || count > AppConfig.SEND_COUNT_MAX) {
            throw new IllegalArgumentException("发送次数必须在 " + AppConfig.SEND_COUNT_MIN + "-"
                    + AppConfig.SEND_COUNT_MAX + " 之间，当前为 " + count);
        }
    }


    private void validateBroadcast(String broadcast) throws IllegalArgumentException {
        if (broadcast == null || broadcast.isBlank()) {
            throw new IllegalArgumentException("广播地址不能为空");
        }
        String trimmed = broadcast.trim();


        if (trimmed.contains("://")) {
            throw new IllegalArgumentException("广播地址不需要协议前缀（如 http://），"
                    + "请直接输入 IP 地址或主机名，例如 10.0.0.255");
        }


        if (trimmed.contains(":")) {
            if (!trimmed.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalArgumentException("IPv6 广播地址包含非法字符：「" + trimmed + "」");
            }
            return;
        }


        if (trimmed.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')) {
            String[] parts = trimmed.split("\\.", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("广播地址必须为 IPv4 格式（如 10.0.0.255），当前为 " + parts.length + " 段");
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


        if (!trimmed.matches("[A-Za-z0-9]([A-Za-z0-9._-]{0,252})")) {
            throw new IllegalArgumentException("广播地址格式不合法：「" + trimmed + "」"
                    + "（应为 IP 地址或主机名，例如 10.0.0.255 / broadcast.local）");
        }
    }
}
