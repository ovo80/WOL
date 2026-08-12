/*
 * WOL 唤醒工具 - WolUtil 单元测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ad.ovo.wol.util.WolUtil;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link WolUtil} 单元测试：MAC 解析、端口边界与魔术包结构（无网络 I/O）。 */
class WolUtilTest {

  @Test
  void parseMac_合法MAC解析为6字节() {
    byte[] mac = WolUtil.parseMac("00:1A:2B:3C:4D:5E");
    assertEquals(6, mac.length);
    assertArrayEquals(new byte[] {(byte) 0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E}, mac);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "00:1A:2B:3C:4D",
        "GG:1A:2B:3C:4D:5E",
        "",
        "00:1A:2B:3C:4D:5",
        "00:1A:2B:3C:4D:5E:",
        ":00:1A:2B:3C:4D:5E"
      })
  void parseMac_非法MAC应被拒绝(String mac) {
    assertThrows(IllegalArgumentException.class, () -> WolUtil.parseMac(mac));
  }

  @Test
  void parseMac_支持连字符分隔() {
    byte[] mac = WolUtil.parseMac("00-1A-2B-3C-4D-5E");
    assertEquals(6, mac.length);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 9, 65535})
  void validatePort_边界值通过(int port) {
    assertDoesNotThrow(() -> WolUtil.validatePort(port));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 65536})
  void validatePort_越界值拒绝(int port) {
    assertThrows(IllegalArgumentException.class, () -> WolUtil.validatePort(port));
  }

  @Test
  void buildMagicPacket_结构应为6个FF加16次MAC共102字节() {
    byte[] mac = WolUtil.parseMac("00:1A:2B:3C:4D:5E");
    byte[] pkt = WolUtil.buildMagicPacket(mac);
    assertEquals(102, pkt.length);
    for (int i = 0; i < 6; i++) {
      assertEquals((byte) 0xFF, pkt[i]);
    }
    for (int r = 0; r < 16; r++) {
      assertArrayEquals(
          mac, Arrays.copyOfRange(pkt, 6 + r * 6, 12 + r * 6), "第 " + r + " 次重复的 MAC 内容应一致");
    }
  }

  @Test
  void buildMagicPacket_空引用或长度错误拒绝() {
    assertThrows(IllegalArgumentException.class, () -> WolUtil.buildMagicPacket(null));
    assertThrows(IllegalArgumentException.class, () -> WolUtil.buildMagicPacket(new byte[5]));
  }
}
