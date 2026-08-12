/*
 * WOL 唤醒工具 - SRV 记录查询与解析（基于 JDK 内置 JNDI DNS）。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.util;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SRV 记录查询与解析工具（纯静态，无实例状态）。
 *
 * <p>底层为 JDK 内置 JNDI DNS（模块 {@code jdk.naming.dns}，零外部依赖）：向系统配置的 DNS 服务器查询 SRV 记录，返回 格式如 {@code "10
 * 50 5060 sip.antisip.com."}（priority weight port target，空格分隔，target 以点结尾）。
 *
 * <p>记录名约定：完整记录名形如 {@code _服务._协议.域名}（如 {@code _wol._udp.example.com}）；输入不带 {@code _} 前缀时自动补全
 * {@code _wol._udp.} 前缀，兼容「只填域名」的快捷输入。
 *
 * <p>异常契约：参数错误、查询失败、记录缺失均抛 {@link IllegalArgumentException}（消息面向用户，可直接展示）；由 Service
 * 层统一转译。线程安全：全部为无状态静态方法，可并发调用。
 */
public final class SrvUtil {

  private static final Logger log = LoggerFactory.getLogger(SrvUtil.class);

  /** 自动补全前缀：服务名 _wol（Wake-on-LAN） */
  private static final String DEFAULT_SERVICE = "_wol";

  /** 自动补全前缀：传输协议 _udp（WOL 走 UDP） */
  private static final String DEFAULT_PROTOCOL = "_udp";

  /** JNDI 环境键：上下文工厂类名 */
  private static final String JNDI_FACTORY = "java.naming.factory.initial";

  /** JNDI DNS 上下文工厂（jdk.naming.dns 模块） */
  private static final String DNS_FACTORY_CLASS = "com.sun.jndi.dns.DnsContextFactory";

  /** JNDI 环境键：提供者 URL */
  private static final String JNDI_PROVIDER = "java.naming.provider.url";

  /** 省略服务器地址：使用系统默认 DNS 配置 */
  private static final String DNS_PROVIDER = "dns:";

  /** 查询属性名：SRV */
  private static final String ATTR_SRV = "SRV";

  /** SRV 值字段数：priority weight port target */
  private static final int FIELD_COUNT = 4;

  /** 记录名合法字符集（字母/数字/点/横线/下划线，1-253 字符） */
  private static final String QUERY_PATTERN = "[A-Za-z0-9._-]{1,253}";

  private SrvUtil() {}

  /** SRV 记录模型：优先级/权重/端口/目标主机名。 */
  public static final class SrvRecord {

    private final int priority;
    private final int weight;
    private final int port;
    private final String target;

    SrvRecord(int priority, int weight, int port, String target) {
      this.priority = priority;
      this.weight = weight;
      this.port = port;
      this.target = target;
    }

    public int getPriority() {
      return priority;
    }

    public int getWeight() {
      return weight;
    }

    public int getPort() {
      return port;
    }

    public String getTarget() {
      return target;
    }

    /**
     * 解析目标主机名为 IP 地址（触发 A/AAAA 记录查询）。
     *
     * @return 解析后的地址
     * @throws IllegalArgumentException 主机名无法解析时；消息含原始主机名
     */
    public InetAddress resolveTarget() throws IllegalArgumentException {
      try {
        return InetAddress.getByName(target);
      } catch (IOException e) {
        throw new IllegalArgumentException("SRV 目标地址「" + target + "」无法解析: " + e.getMessage(), e);
      }
    }

    /**
     * @return 展示文本 {@code 主机名:端口}（如 sip.antisip.com:5060）
     */
    public String display() {
      return target + ":" + port;
    }
  }

  /**
   * 规范化 SRV 记录名输入：去首尾空白；不以 {@code _} 开头时自动补 {@code _wol._udp.} 前缀。
   *
   * @param input 用户输入；可为 null
   * @return 完整记录名；null/空白输入返回空串（由调用方决定提示文案）
   */
  public static String normalizeQuery(String input) {
    if (input == null || input.isBlank()) {
      return "";
    }
    String trimmed = input.trim();
    return trimmed.startsWith("_")
        ? trimmed
        : DEFAULT_SERVICE + "." + DEFAULT_PROTOCOL + "." + trimmed;
  }

  /**
   * 解析单条 SRV 值（JNDI 返回值）为记录。
   *
   * <p>数据契约：值为 {@code "priority weight port target"} 四段空白分隔文本（target 可带结尾点）；JNDI 返回类型可能是 String 或
   * byte[]，两者均支持。
   *
   * @param raw JNDI 返回的 SRV 值；null 视为非法
   * @return 解析后的记录；target 已去除结尾点
   * @throws IllegalArgumentException 字段数不足、数值非十进制/越界（priority/weight 0-65535，port 1-65535）或 target
   *     为空时；消息含具体原因
   */
  public static SrvRecord parseSrvValue(Object raw) throws IllegalArgumentException {
    String text;
    if (raw instanceof String s) {
      text = s;
    } else if (raw instanceof byte[] bytes) {
      text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } else {
      throw new IllegalArgumentException(
          "SRV 记录值类型不受支持：" + (raw == null ? "null" : raw.getClass().getSimpleName()));
    }

    String[] fields = text.trim().split("\\s+");
    if (fields.length != FIELD_COUNT) {
      throw new IllegalArgumentException("SRV 记录字段数错误，应为 " + FIELD_COUNT + " 段：" + text.trim());
    }
    int priority = parseRange(fields[0], 0, 65535, "priority");
    int weight = parseRange(fields[1], 0, 65535, "weight");
    // SRV 语义中 port 0 表示服务不可用，故下界为 1
    int port = parseRange(fields[2], 1, 65535, "port");
    String target =
        fields[3].endsWith(".") ? fields[3].substring(0, fields[3].length() - 1) : fields[3];
    if (target.isBlank()) {
      throw new IllegalArgumentException("SRV 记录目标主机为空：" + text.trim());
    }
    return new SrvRecord(priority, weight, port, target);
  }

  /**
   * 从候选记录中选出最优：优先级（priority）最小者优先，同优先级取权重（weight）最大者。
   *
   * @param records 候选列表（非空）
   * @return 最优记录
   * @throws IllegalArgumentException 列表为 null 或为空时
   */
  public static SrvRecord selectBest(List<SrvRecord> records) throws IllegalArgumentException {
    if (records == null || records.isEmpty()) {
      throw new IllegalArgumentException("未找到可用的 SRV 记录");
    }
    return records.stream()
        .min(
            Comparator.comparingInt(SrvRecord::getPriority)
                .thenComparing(Comparator.comparingInt(SrvRecord::getWeight).reversed()))
        .orElseThrow(() -> new IllegalArgumentException("未找到可用的 SRV 记录"));
  }

  /**
   * 查询并解析 SRV 记录，返回最优记录。
   *
   * <p>查询链路：{@link #normalizeQuery(String)} → 格式校验 → JNDI DNS 查询（网络 I/O，阻塞，调用方应置于后台线程）。
   *
   * @param input SRV 记录名或域名（自动补前缀，见 {@link #normalizeQuery(String)}）
   * @return 最优 SRV 记录（priority 最小、weight 最大）
   * @throws IllegalArgumentException 输入为空白、格式非法、记录不存在、查询失败或解析失败时；消息含具体原因
   */
  public static SrvRecord resolve(String input) throws IllegalArgumentException {
    String query = normalizeQuery(input);
    if (query.isEmpty()) {
      throw new IllegalArgumentException("SRV 地址不能为空");
    }
    if (!query.matches(QUERY_PATTERN)) {
      throw new IllegalArgumentException("SRV 地址格式不合法：「" + input + "」（应形如 _wol._udp.example.com）");
    }

    Hashtable<String, String> env = new Hashtable<>();
    env.put(JNDI_FACTORY, DNS_FACTORY_CLASS);
    env.put(JNDI_PROVIDER, DNS_PROVIDER);
    try {
      DirContext ctx = new InitialDirContext(env);
      try {
        Attributes attrs = ctx.getAttributes(query, new String[] {ATTR_SRV});
        Attribute srv = attrs.get(ATTR_SRV);
        if (srv == null) {
          // 查询成功但无 SRV 属性（如仅有 A 记录）：视为记录不存在
          throw new IllegalArgumentException("未找到 SRV 记录：「" + input + "」");
        }
        List<SrvRecord> records = new ArrayList<>();
        NamingEnumeration<?> values = srv.getAll();
        while (values.hasMore()) {
          records.add(parseSrvValue(values.next()));
        }
        return selectBest(records);
      } finally {
        ctx.close();
      }
    } catch (NameNotFoundException e) {
      // response code 3：DNS 权威回答该记录名不存在
      throw new IllegalArgumentException("SRV 记录不存在：「" + input + "」", e);
    } catch (NamingException e) {
      log.warn("SRV 查询失败: query={}", query, e);
      throw new IllegalArgumentException("SRV 查询失败：" + e.getMessage(), e);
    } catch (NoClassDefFoundError e) {
      // jdk.naming.dns 模块缺失（如 jpackage 未加入 --add-modules）时 JNDI 工厂加载失败
      log.error("JNDI DNS 不可用（缺少 jdk.naming.dns 模块？）", e);
      throw new IllegalArgumentException("SRV 解析不可用：缺少 JDK DNS 模块", e);
    }
  }

  /**
   * 解析 16 位无符号字段并校验闭区间。
   *
   * @param raw 十进制文本
   * @param min 合法下界（含）
   * @param max 合法上界（含）
   * @param label 出错消息中的字段名
   * @return 解析值
   * @throws IllegalArgumentException 非十进制或越界时
   */
  private static int parseRange(String raw, int min, int max, String label)
      throws IllegalArgumentException {
    try {
      int value = Integer.parseInt(raw);
      if (value >= min && value <= max) {
        return value;
      }
    } catch (NumberFormatException ignored) {
    }
    throw new IllegalArgumentException(
        "SRV 记录 " + label + " 非法：「" + raw + "」（合法范围 " + min + "-" + max + "）");
  }
}
