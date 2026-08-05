package ad.ovo.wol;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.util.WolUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;


public class SmokeCheck {

    public static void main(String[] args) throws Exception {

        byte[] mac = WolUtil.parseMac("00:1A:2B:3C:4D:5E");
        expect(mac.length == 6, "合法 MAC 应解析为 6 字节");
        System.out.println("[OK] parseMac 合法 MAC -> " + mac.length + " 字节");

        expectRejected("00:1A:2B:3C:4D", "组数不足");
        expectRejected("GG:1A:2B:3C:4D:5E", "非十六进制");
        expectRejected("", "空值");
        expectRejected("00:1A:2B:3C:4D:5", "组长度错误");
        expectRejected("00:1A:2B:3C:4D:5E:", "尾随分隔符");
        expectRejected(":00:1A:2B:3C:4D:5E", "前导分隔符");


        WolUtil.validatePort(1);
        WolUtil.validatePort(9);
        WolUtil.validatePort(65535);
        System.out.println("[OK] 端口边界值校验通过 (1/9/65535)");
        expectRejectedPort(0);
        expectRejectedPort(-1);
        expectRejectedPort(65536);


        byte[] pkt = WolUtil.buildMagicPacket(mac);
        expect(pkt.length == 102, "魔术包应为 102 字节");
        for (int i = 0; i < 6; i++) {
            expect(pkt[i] == (byte) 0xFF, "前 6 字节应为 0xFF");
        }
        for (int r = 0; r < 16; r++) {
            expect(Arrays.equals(Arrays.copyOfRange(pkt, 6 + r * 6, 12 + r * 6), mac),
                    "第 " + r + " 次重复的 MAC 内容应一致");
        }
        System.out.println("[OK] 魔术包结构正确: 6*FF + MAC*16 = 102 字节");


        int testPort = 19009;
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress("0.0.0.0", testPort))) {
            receiver.setSoTimeout(4000);
            final boolean[] matched = {false};
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[128];
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    receiver.receive(dp);
                    matched[0] = Arrays.equals(
                            Arrays.copyOfRange(dp.getData(), 0, dp.getLength()), pkt);
                } catch (Exception e) {
                    System.out.println("[FAIL] 接收端异常: " + e);
                }
            });
            t.start();
            Thread.sleep(300);

            WolUtil.sendWOL("00:1A:2B:3C:4D:5E", "127.0.0.1", testPort);
            t.join(6000);
            expect(matched[0], "接收端应收到与发送内容一致的 102 字节数据包（自定义端口 " + testPort + "）");
            System.out.println("[OK] 自定义端口 " + testPort + " 收发内容一致");
        }


        WolService service = new WolService();
        Device dLocalhost = makeDevice("00:11:22:33:44:55", "localhost", 19010);

        service.sendWakeUp(dLocalhost, 1);
        System.out.println("[OK] 域名主机名 localhost 校验并发送通过");

        service.sendWakeUp(makeDevice("00:11:22:33:44:55", "::1", 19010), 1);
        System.out.println("[OK] IPv6 字面量 ::1 校验并发送通过");

        expectRejectedBroadcast(service, "http://10.0.0.255", "URL 协议前缀");
        expectRejectedBroadcast(service, "https://broadcast.local", "URL 协议前缀");

        expectRejectedBroadcast(service, "256.1.1.1", "IPv4 段越界");
        expectRejectedBroadcast(service, "10.0.0", "IPv4 段数不足");
        expectRejectedBroadcast(service, "10.0.0.255.1", "IPv4 段数过多");

        expectRejectedBroadcast(service, "bad host!", "含空格");
        expectRejectedBroadcast(service, "", "空值");


        int burstPort = 19012;
        int burstCount = 3;
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress("0.0.0.0", burstPort))) {
            receiver.setSoTimeout(8000);
            final int[] received = {0};
            final boolean[] allMatch = {true};
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < burstCount; i++) {
                        byte[] buf = new byte[128];
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        receiver.receive(dp);
                        received[0]++;
                        allMatch[0] &= Arrays.equals(
                                Arrays.copyOfRange(dp.getData(), 0, dp.getLength()), pkt);
                    }
                } catch (Exception e) {
                    System.out.println("[FAIL] 连发接收端异常: " + e);
                }
            });
            t.start();
            Thread.sleep(300);
            service.sendWakeUp(makeDevice("00:1A:2B:3C:4D:5E", "127.0.0.1", burstPort), burstCount);
            t.join(9000);
            expect(received[0] == burstCount, "应收到 " + burstCount + " 个包，实际 " + received[0]);
            expect(allMatch[0], "连发的每个包内容都应一致");
            System.out.println("[OK] 连发 " + burstCount + " 个包全部收到且内容一致");
        }

        expectRejectedCount(service, 0);
        expectRejectedCount(service, 101);


        DeviceConfig saved = new DeviceConfig();
        Device pc = makeDevice("00:1A:2B:3C:4D:5E", "10.0.0.255", 9);
        pc.setName("书房电脑");
        Device nas = makeDevice("AA:BB:CC:DD:EE:FF", "192.168.1.255", 7);
        nas.setName("客厅 NAS");
        saved.addDevice(pc);
        saved.addDevice(nas);
        saved.setSendCount(3);
        saved.save();

        DeviceConfig loaded = DeviceConfig.load();
        expect(loaded.getDevices().size() == 2, "应加载回 2 台设备，实际 " + loaded.getDevices().size());
        expect(loaded.getDevices().get(0).getName().equals("书房电脑"), "第 1 台设备名应保持");
        expect(loaded.getDevices().get(0).getMacAddress().equals("00:1A:2B:3C:4D:5E"), "第 1 台 MAC 应保持");
        expect(loaded.getDevices().get(1).getPort() == 7, "第 2 台端口应保持 7");
        expect(loaded.getSendCount() == 3, "全局连发次数应保持 3");
        expect(loaded.getTheme().equals("dark"), "主题默认应为 dark");
        expect(DeviceConfig.getConfigPath().toFile().exists(), "配置文件应已创建");
        System.out.println("[OK] 多设备配置往返读写一致: " + DeviceConfig.getConfigPath()
                + " (" + loaded.getDevices().size() + " 台设备)");


        DeviceConfig themed = DeviceConfig.load();
        themed.setTheme(AppConfig.THEME_LIGHT);
        themed.save();
        DeviceConfig reloaded = DeviceConfig.load();
        expect(reloaded.getTheme().equals(AppConfig.THEME_LIGHT),
                "浅色主题保存后应被保留，实际 " + reloaded.getTheme());
        System.out.println("[OK] 主题偏好持久化往返一致: " + reloaded.getTheme());


        expect(!DeviceConfig.getConfigPath().resolveSibling("device.properties.tmp").toFile().exists(),
                "保存后不应残留 .tmp 临时文件");
        System.out.println("[OK] 原子写入无临时文件残留");


        try {
            new DeviceConfig().getDevices().add(new Device());
            throw new AssertionError("getDevices() 应返回只读视图");
        } catch (UnsupportedOperationException e) {
            System.out.println("[OK] getDevices() 为只读视图，外部修改被拒绝");
        }


        DeviceConfig restore = new DeviceConfig();
        restore.addDevice(new Device());
        restore.setSendCount(AppConfig.DEFAULT_SEND_COUNT);
        restore.save();

        System.out.println("SMOKE CHECK ALL PASSED");
    }

    private static Device makeDevice(String mac, String broadcast, int port) {
        Device d = new Device();
        d.setMacAddress(mac);
        d.setBroadcastAddress(broadcast);
        d.setPort(port);
        return d;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectRejected(String mac, String scenario) {
        try {
            WolUtil.parseMac(mac);
            throw new AssertionError(scenario + " 应被拒绝: " + mac);
        } catch (IllegalArgumentException e) {
            System.out.println("[OK] 非法 MAC(" + scenario + ") 拒绝: " + e.getMessage());
        }
    }

    private static void expectRejectedPort(int port) {
        try {
            WolUtil.validatePort(port);
            throw new AssertionError("端口 " + port + " 应被拒绝");
        } catch (IllegalArgumentException e) {
            System.out.println("[OK] 非法端口 " + port + " 拒绝: " + e.getMessage());
        }
    }

    private static void expectRejectedBroadcast(WolService service, String broadcast, String scenario) {
        try {
            service.sendWakeUp(makeDevice("00:11:22:33:44:55", broadcast, 9), 1);
            throw new AssertionError("广播地址「" + broadcast + "」(" + scenario + ") 应被拒绝");
        } catch (WolException e) {
            System.out.println("[OK] 非法广播地址(" + scenario + ") 拒绝: " + e.getMessage());
        }
    }

    private static void expectRejectedCount(WolService service, int count) {
        try {
            service.sendWakeUp(makeDevice("00:11:22:33:44:55", "127.0.0.1", 9), count);
            throw new AssertionError("发送次数 " + count + " 应被拒绝");
        } catch (WolException e) {
            System.out.println("[OK] 非法发送次数 " + count + " 拒绝: " + e.getMessage());
        }
    }
}
