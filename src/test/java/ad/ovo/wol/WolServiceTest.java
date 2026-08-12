/*
 * WOL 唤醒工具 - WolService 集成测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ad.ovo.wol.common.exception.WolException;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.service.WolService;
import ad.ovo.wol.service.impl.WolServiceImpl;
import ad.ovo.wol.util.WolUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link WolService} 集成测试：广播地址校验与真实 UDP 收发。
 *
 * <p>发送目标为本机回环（127.0.0.1），用 DatagramSocket 收包断言内容一致，不依赖外部网络环境。
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
        () -> service.sendWakeUp(makeDevice("00:11:22:33:44:55", broadcast, 9), 1),
        scenario);
  }

  @ParameterizedTest
  @CsvSource({"0, 次数下界", "101, 次数上界"})
  void sendWakeUp_非法发送次数应被拒绝(int count, String scenario) {
    assertThrows(
        WolException.class,
        () -> service.sendWakeUp(makeDevice("00:11:22:33:44:55", "127.0.0.1", 9), count),
        scenario);
  }

  @Test
  void sendWakeUpViaSrv_空SRV地址被拒绝() {
    assertThrows(WolException.class, () -> service.sendWakeUpViaSrv("00:11:22:33:44:55", "", 1));
    assertThrows(WolException.class, () -> service.sendWakeUpViaSrv("00:11:22:33:44:55", "   ", 1));
  }

  @Test
  void sendWakeUpViaSrv_非法MAC被拒绝() {
    assertThrows(
        WolException.class, () -> service.sendWakeUpViaSrv("bad-mac", "_wol._udp.example.com", 1));
  }

  @ParameterizedTest
  @CsvSource({"0, 次数下界", "101, 次数上界"})
  void sendWakeUpViaSrv_非法发送次数被拒绝(int count, String scenario) {
    assertThrows(
        WolException.class,
        () -> service.sendWakeUpViaSrv("00:11:22:33:44:55", "_wol._udp.example.com", count),
        scenario);
  }

  @Test
  void sendWakeUp_设备开启SRV模式时走SRV校验链路() {
    // 校验在 DNS 查询前完成：SRV 地址空白即失败，不触发网络查询
    Device d = makeDevice("00:11:22:33:44:55", "10.0.0.255", 9);
    d.setSrvEnabled(true);
    assertThrows(WolException.class, () -> service.sendWakeUp(d, 1));
  }

  @Test
  void sendWakeUp_自定义端口单发内容一致() throws Exception {
    byte[] pkt = WolUtil.buildMagicPacket(WolUtil.parseMac("00:1A:2B:3C:4D:5E"));
    try (DatagramSocket receiver = new DatagramSocket(0)) {
      receiver.setSoTimeout(8000);
      Thread sender = new Thread(() -> sendSilently(receiver.getLocalPort(), 1));
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
      Thread sender = new Thread(() -> sendSilently(receiver.getLocalPort(), burstCount));
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

  private void sendSilently(int port, int count) {
    try {
      service.sendWakeUp(makeDevice("00:1A:2B:3C:4D:5E", "127.0.0.1", port), count);
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
}
