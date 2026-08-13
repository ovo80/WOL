/*
 * WOL 唤醒工具 - WolService 集成测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ad.ovo.wol.common.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.plugin.SendMode;
import ad.ovo.wol.plugin.Target;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.service.impl.WolServiceImpl;
import ad.ovo.wol.util.WolUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link WolService} 集成测试：广播地址校验与真实 UDP 收发。
 *
 * <p>发送目标为本机回环（127.0.0.1），用 DatagramSocket 收包断言内容一致，不依赖外部网络环境。自定义模式用测试用 {@link
 * TestSendMode} 验证委托链路（目标解析在插件侧，核心负责连发）。
 */
class WolServiceTest {

  private final WolService service = new WolServiceImpl();

  @ParameterizedTest
  @CsvSource({
    "http://10.0.0.255, URL协议前缀",
    "https://broadcast.local, URL协议前缀",
    "256.1.1.1, IPv4段越界",
    "10.0.0, IPv4段数不足",
    "10.0.0.255.1, IPv4段数过多",
    "bad host!, 含空格",
    ", 空值",
    "10.0.0.999, 段值越界"
  })
  void sendWakeUp_非法广播地址应被拒绝(String broadcast, String scenario) {
    assertThrows(
        WolException.class,
        () -> service.sendWakeUp(makeDevice("00:11:22:33:44:55", broadcast, 9), 1, null),
        scenario);
  }

  @ParameterizedTest
  @CsvSource({"0, 次数下界", "101, 次数上界"})
  void sendWakeUp_非法发送次数应被拒绝(int count, String scenario) {
    assertThrows(
        WolException.class,
        () -> service.sendWakeUp(makeDevice("00:11:22:33:44:55", "127.0.0.1", 9), count, null),
        scenario);
  }

  @Test
  void sendWakeUp_自定义模式但插件未提供时被拒绝() {
    Device d = makeDevice("00:11:22:33:44:55", "10.0.0.255", 9);
    d.setMode("custom-mode");
    d.setModeValue("custom-data");
    // 校验在解析前完成：sendMode 为 null 即失败，不触发网络查询
    assertThrows(WolException.class, () -> service.sendWakeUp(d, 1, null));
  }

  @Test
  void sendWakeUp_自定义模式委托解析目标并连发() throws Exception {
    byte[] pkt = WolUtil.buildMagicPacket(WolUtil.parseMac("00:1A:2B:3C:4D:5E"));
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      receiver.setSoTimeout(8000);
      SendMode mode = new TestSendMode(receiver.getLocalPort());
      Device d = makeDevice("00:1A:2B:3C:4D:5E", "10.0.0.255", 9);
      d.setMode("test");
      d.setModeValue("some-value");
      Thread sender = new Thread(() -> sendSilently(d, 1, mode));
      sender.start();
      DatagramPacket dp = new DatagramPacket(new byte[128], 128);
      receiver.receive(dp);
      assertArrayEquals(pkt, Arrays.copyOfRange(dp.getData(), 0, dp.getLength()));
      sender.join(8000);
      // 回显文本由 SendMode 提供，直接验证同步发送的返回值
      assertEquals("127.0.0.1:" + receiver.getLocalPort(), service.sendWakeUp(d, 1, mode));
    }
  }

  @Test
  void sendWakeUp_自定义端口单发内容一致() throws Exception {
    byte[] pkt = WolUtil.buildMagicPacket(WolUtil.parseMac("00:1A:2B:3C:4D:5E"));
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      receiver.setSoTimeout(8000);
      Thread sender =
          new Thread(() -> sendSilently(makeDevice("00:1A:2B:3C:4D:5E", "127.0.0.1", receiver.getLocalPort()), 1, null));
      sender.start();
      DatagramPacket dp = new DatagramPacket(new byte[128], 128);
      receiver.receive(dp);
      assertArrayEquals(pkt, Arrays.copyOfRange(dp.getData(), 0, dp.getLength()));
      sender.join(8000);
    }
  }

  @Test
  void sendWakeUp_连发N包全部收到且内容一致() throws Exception {
    byte[] pkt = WolUtil.buildMagicPacket(WolUtil.parseMac("00:1A:2B:3C:4D:5E"));
    int burstCount = 3;
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      receiver.setSoTimeout(8000);
      Thread sender =
          new Thread(
              () -> sendSilently(makeDevice("00:1A:2B:3C:4D:5E", "127.0.0.1", receiver.getLocalPort()), burstCount, null));
      sender.start();
      for (int i = 0; i < burstCount; i++) {
        DatagramPacket dp = new DatagramPacket(new byte[128], 128);
        receiver.receive(dp);
        assertArrayEquals(
            pkt, Arrays.copyOfRange(dp.getData(), 0, dp.getLength()), "第 " + (i + 1) + " 个包内容应一致");
      }
      sender.join(8000);
    }
  }

  private void sendSilently(Device device, int count, SendMode mode) {
    try {
      service.sendWakeUp(device, count, mode);
    } catch (WolException e) {
      throw new RuntimeException(e);
    }
  }

  /** 构造测试设备（MAC 固定，广播/端口可配）。 */
  private Device makeDevice(String mac, String broadcast, int port) {
    Device d = new Device();
    d.setMacAddress(mac);
    d.setBroadcastAddress(broadcast);
    d.setPort(port);
    return d;
  }

  /** 测试用发送模式：解析为固定回环地址与指定端口（不触发网络 I/O）。 */
  private static final class TestSendMode implements SendMode {

    private final int port;

    TestSendMode(int port) {
      this.port = port;
    }

    @Override
    public String id() {
      return "test";
    }

    @Override
    public String name() {
      return "测试模式";
    }

    @Override
    public String description() {
      return "单元测试用";
    }

    @Override
    public String broadcastLabel() {
      return "测试数据";
    }

    @Override
    public String broadcastPrompt() {
      return "输入任意值";
    }

    @Override
    public String portLabel() {
      return "解析目标";
    }

    @Override
    public String portPrompt() {
      return "解析后回显";
    }

    @Override
    public boolean usesPortField() {
      return false;
    }

    @Override
    public Target resolve(String modeValue) throws WolException {
      try {
        return new Target(InetAddress.getByName("127.0.0.1"), port, "127.0.0.1:" + port);
      } catch (Exception e) {
        throw new WolException("解析失败", e);
      }
    }
  }
}
