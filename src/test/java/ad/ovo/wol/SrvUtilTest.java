/*
 * WOL 唤醒工具 - SrvUtil 纯函数测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ad.ovo.wol.util.SrvUtil;
import ad.ovo.wol.util.SrvUtil.SrvRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link SrvUtil} 纯函数测试：记录名规范化、SRV 值解析与选优。
 *
 * <p>只覆盖不触发网络查询的路径（normalizeQuery/parseSrvValue/selectBest 及 resolve 的本地校验分支），真实 DNS 查询
 * 不纳入单测（依赖外部网络环境，测试须可离线复现）。
 */
class SrvUtilTest {

  @Test
  void normalizeQuery_域名自动补全服务与协议前缀() {
    assertEquals("_wol._udp.example.com", SrvUtil.normalizeQuery("example.com"));
  }

  @Test
  void normalizeQuery_完整记录名保持不变() {
    assertEquals("_wol._udp.example.com", SrvUtil.normalizeQuery("_wol._udp.example.com"));
  }

  @Test
  void normalizeQuery_其他服务前缀保持不变() {
    assertEquals(
        "_minecraft._tcp.mc.example.com", SrvUtil.normalizeQuery("_minecraft._tcp.mc.example.com"));
  }

  @Test
  void normalizeQuery_去首尾空白() {
    assertEquals("_wol._udp.example.com", SrvUtil.normalizeQuery("  example.com  "));
  }

  @Test
  void normalizeQuery_null与空白返回空串() {
    assertEquals("", SrvUtil.normalizeQuery(null));
    assertEquals("", SrvUtil.normalizeQuery("   "));
  }

  @Test
  void parseSrvValue_标准格式解析正确() {
    SrvRecord record = SrvUtil.parseSrvValue("10 50 5060 sip.antisip.com.");
    assertEquals(10, record.getPriority());
    assertEquals(50, record.getWeight());
    assertEquals(5060, record.getPort());
    assertEquals("sip.antisip.com", record.getTarget());
    assertEquals("sip.antisip.com:5060", record.display());
  }

  @Test
  void parseSrvValue_容忍多个连续空格() {
    SrvRecord record = SrvUtil.parseSrvValue("30  30  5269  scarlet.jabber.org.");
    assertEquals(30, record.getPriority());
    assertEquals(5269, record.getPort());
    assertEquals("scarlet.jabber.org", record.getTarget());
  }

  @Test
  void parseSrvValue_target不带结尾点也可解析() {
    SrvRecord record = SrvUtil.parseSrvValue("0 0 9 wol.example.com");
    assertEquals("wol.example.com", record.getTarget());
  }

  @Test
  void parseSrvValue_支持byte数组值() {
    byte[] raw = "1 0 9000 node1.example.net.".getBytes(StandardCharsets.UTF_8);
    SrvRecord record = SrvUtil.parseSrvValue(raw);
    assertEquals(9000, record.getPort());
    assertEquals("node1.example.net", record.getTarget());
  }

  @Test
  void parseSrvValue_端口0表示服务不可用应拒绝() {
    assertThrows(
        IllegalArgumentException.class, () -> SrvUtil.parseSrvValue("0 0 0 host.example.com."));
  }

  @ParameterizedTest
  @CsvSource({
    "10 50 host.example.com., 字段数不足",
    "10 50 70000 host.example.com., 端口越界",
    "10 50 abc host.example.com., 端口非数字",
    "10 50 5060 , 目标为空",
    "65536 0 9 host.example.com., 优先级越界",
    "0 70000 9 host.example.com., 权重越界"
  })
  void parseSrvValue_非法值应拒绝(String raw, String scenario) {
    assertThrows(IllegalArgumentException.class, () -> SrvUtil.parseSrvValue(raw), scenario);
  }

  @Test
  void parseSrvValue_null应拒绝() {
    assertThrows(IllegalArgumentException.class, () -> SrvUtil.parseSrvValue(null));
  }

  @Test
  void selectBest_取优先级最小记录() {
    List<SrvRecord> records =
        List.of(
            SrvUtil.parseSrvValue("20 0 5060 a.example.com."),
            SrvUtil.parseSrvValue("5 0 5061 b.example.com."),
            SrvUtil.parseSrvValue("10 0 5062 c.example.com."));
    assertEquals("b.example.com:5061", SrvUtil.selectBest(records).display());
  }

  @Test
  void selectBest_同优先级取权重最大记录() {
    List<SrvRecord> records =
        List.of(
            SrvUtil.parseSrvValue("10 30 5060 a.example.com."),
            SrvUtil.parseSrvValue("10 80 5061 b.example.com."),
            SrvUtil.parseSrvValue("10 20 5062 c.example.com."));
    assertEquals("b.example.com:5061", SrvUtil.selectBest(records).display());
  }

  @Test
  void selectBest_空列表应拒绝() {
    assertThrows(IllegalArgumentException.class, () -> SrvUtil.selectBest(List.of()));
  }

  @Test
  void resolve_空白输入本地拒绝() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> SrvUtil.resolve("  "));
    assertEquals("SRV 地址不能为空", e.getMessage());
  }

  @Test
  void resolve_非法字符本地拒绝() {
    assertThrows(IllegalArgumentException.class, () -> SrvUtil.resolve("bad host!.com"));
    assertThrows(IllegalArgumentException.class, () -> SrvUtil.resolve("http://example.com"));
  }
}
