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

/**
 * 业务服务层：串联 Model 与底层工具，负责输入校验编排、异常转译与日志记录。
 * <p>Controller 不直接接触 WolUtil / DeviceConfig 的底层细节，全部经由本服务。</p>
 */
public class WolService {

    private static final Logger log = LoggerFactory.getLogger(WolService.class);

    /**
     * 校验并连发 N 个 WOL 魔术包（供后台 Task 调用，面向设备模型）。
     *
     * @param device 目标设备（mac / broadcast / port）
     * @param count  连发次数（1-100）
     * @throws WolException 校验失败或网络发送失败（消息可直接展示给用户）
     */
    public void sendWakeUp(Device device, int count) throws WolException {
        sendWakeUp(device.getMacAddress(), device.getBroadcastAddress(), device.getPort(), count);
    }

    /**
     * 校验并连发 N 个 WOL 魔术包（供后台 Task 调用）。
     * <p>按需求以指定次数连续发送（默认 5 次），每次间隔 {@link AppConfig#SEND_INTERVAL_MS}，
     * 提高目标主机收到魔术包的概率（WOL 惯例）。</p>
     * <p>连发全程<b>只解析一次地址、只创建一个 Socket</b>，避免 N 次 DNS 查询与 Socket 创建开销。</p>
     *
     * @param mac       目标主机 MAC 地址
     * @param broadcast 子网广播地址
     * @param port      目标 UDP 端口
     * @param count     连发次数（1-100）
     * @throws WolException 校验失败或网络发送失败（消息可直接展示给用户）
     */
    public void sendWakeUp(String mac, String broadcast, int port, int count) throws WolException {
        // 1) 参数校验与一次性准备（抛 IllegalArgumentException，统一转译）。
        //    校验顺序与 UI 层提示优先级一致：MAC → 端口 → 次数 → 广播地址
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

        // 2) 单 Socket 连发 N 次（网络异常统一转译为业务异常，并标明失败于第几次）
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

    /** 加载持久化配置 */
    public DeviceConfig loadConfig() {
        DeviceConfig config = DeviceConfig.load();
        log.debug("配置加载完成: {}", DeviceConfig.getConfigPath());
        return config;
    }

    /** 保存配置（失败抛业务异常） */
    public void saveConfig(DeviceConfig config) throws WolException {
        try {
            config.save();
        } catch (IOException e) {
            log.error("配置保存失败: {}", DeviceConfig.getConfigPath(), e);
            throw new WolException("配置保存失败：" + e.getMessage(), e);
        }
    }

    /**
     * 发送次数校验（1-100）。
     *
     * @throws IllegalArgumentException 次数越界
     */
    private void validateCount(int count) throws IllegalArgumentException {
        if (count < AppConfig.SEND_COUNT_MIN || count > AppConfig.SEND_COUNT_MAX) {
            throw new IllegalArgumentException("发送次数必须在 " + AppConfig.SEND_COUNT_MIN + "-"
                    + AppConfig.SEND_COUNT_MAX + " 之间，当前为 " + count);
        }
    }

    /**
     * 广播地址格式校验。
     * <p>支持三种形式：</p>
     * <ul>
     *   <li>IPv4 字面量：点分十进制，每段 0-255（严格校验）</li>
     *   <li>IPv6 字面量：如 {@code ff02::1}（由 {@link java.net.InetAddress} 解析校验）</li>
     *   <li>主机名 / 域名：如 {@code broadcast.example.com}（由 DNS 解析，解析失败在发送时报错）</li>
     * </ul>
     *
     * @throws IllegalArgumentException 格式不合法
     */
    private void validateBroadcast(String broadcast) throws IllegalArgumentException {
        if (broadcast == null || broadcast.isBlank()) {
            throw new IllegalArgumentException("广播地址不能为空");
        }
        String trimmed = broadcast.trim();

        // 友好提示：广播地址不是网页 URL，不需要协议前缀
        if (trimmed.contains("://")) {
            throw new IllegalArgumentException("广播地址不需要协议前缀（如 http://），"
                    + "请直接输入 IP 地址或主机名，例如 10.0.0.255");
        }

        // IPv6 字面量（含冒号）：粗校验后交由 InetAddress 精确解析
        if (trimmed.contains(":")) {
            if (!trimmed.matches("[0-9A-Fa-f:.]+")) {
                throw new IllegalArgumentException("IPv6 广播地址包含非法字符：「" + trimmed + "」");
            }
            return;
        }

        // IPv4 字面量：严格校验四段 0-255
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

        // 主机名 / 域名：粗校验字符集（首字符不能为 '-' 或 '.'），DNS 解析留给发送阶段
        if (!trimmed.matches("[A-Za-z0-9]([A-Za-z0-9._-]{0,252})")) {
            throw new IllegalArgumentException("广播地址格式不合法：「" + trimmed + "」"
                    + "（应为 IP 地址或主机名，例如 10.0.0.255 / broadcast.local）");
        }
    }
}
