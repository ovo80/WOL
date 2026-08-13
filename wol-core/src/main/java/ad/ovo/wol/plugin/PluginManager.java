/*
 * WOL 唤醒工具 - 插件加载与生命周期管理。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol.plugin;

import ad.ovo.wol.common.config.AppConfig;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件管理器：扫描 {@code mods} 目录下的 jar，用 {@link ServiceLoader} 发现并实例化 {@link Mod} 实现，管理启用/禁用生命周期。
 *
 * <p>目录约定：{@code <configDir>/mods}（configDir 默认 {@code ~/.wol}），缺失时自动创建。加载顺序按 jar 文件名字典序，保证确定性。
 *
 * <p>失败语义：单个 jar 加载失败（无注册文件、实现类缺失、构造抛异常）只记录告警并跳过，不影响其余插件与应用启动。
 *
 * <p>线程安全：{@link #scan()} 与 {@link #setEnabled(String, boolean)} 均非线程安全，约定仅在 FX 线程调用。
 */
public final class PluginManager {

  private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

  private final Path modsDir;
  private final Map<String, Mod> mods = new LinkedHashMap<>();
  private final Set<String> enabledIds = new LinkedHashSet<>();
  private URLClassLoader classLoader;

  /** @param configDir 应用配置目录（{@code ~/.wol}），mods 目录在其下 */
  public PluginManager(Path configDir) {
    this.modsDir = configDir.resolve(AppConfig.MODS_DIR_NAME);
  }

  /**
   * 扫描 mods 目录：发现全部 {@link Mod} 实现（不启用）。
   *
   * <p>副作用：I/O 读取目录与 jar；首次调用后持有对 mods 目录 jar 的 URLClassLoader 引用，插件类可正常解析。重复调用会重新扫描（覆盖已有结果）。
   */
  public void scan() {
    List<Path> jars = listJars();
    // 重新扫描前对已启用插件触发禁用回调，与 setEnabled(false) 语义一致（onDisable 契约要求幂等）
    for (String id : enabledIds) {
      Mod mod = mods.get(id);
      if (mod != null) {
        mod.onDisable();
      }
    }
    mods.clear();
    enabledIds.clear();
    closeClassLoader();
    if (jars.isEmpty()) {
      log.debug("mods 目录无插件 jar: {}", modsDir);
      return;
    }

    classLoader = newClassLoader(jars);
    try {
      ServiceLoader<Mod> loader = ServiceLoader.load(Mod.class, classLoader);
      for (Mod mod : loader) {
        if (mod.id() == null || mod.id().isBlank()) {
          log.warn("忽略未声明 id 的插件: {}", mod.getClass().getName());
          continue;
        }
        if (mods.putIfAbsent(mod.id(), mod) != null) {
          log.warn("忽略重复插件 id: {}（{} 与已加载插件冲突）", mod.id(), mod.getClass().getName());
          continue;
        }
        log.info("发现插件: {} v{} ({})", mod.name(), mod.version(), mod.id());
      }
    } catch (ServiceConfigurationError e) {
      // 单个 provider 的实例化失败会以 ServiceConfigurationError 抛到这里，跳过即可
      log.warn("插件实例化失败，已跳过: {}", e.toString());
    }
  }

  /** @return 已发现插件列表（按展示名排序，不可修改） */
  public List<Mod> getMods() {
    List<Mod> result = new ArrayList<>(mods.values());
    result.sort(Comparator.comparing(Mod::name));
    return Collections.unmodifiableList(result);
  }

  /** @return 当前处于启用状态的插件 id 集合（不可修改视图） */
  public Set<String> getEnabledIds() {
    return Collections.unmodifiableSet(enabledIds);
  }

  /**
   * @param id 插件 id
   * @return 该插件当前是否启用
   */
  public boolean isEnabled(String id) {
    return enabledIds.contains(id);
  }

  /**
   * 按模式 id 查找已启用插件提供的发送模式。
   *
   * @param modeId 模式 id（对应 {@link SendMode#id()}）
   * @return 匹配的发送模式；无插件提供该模式或插件未启用时返回 null
   */
  public SendMode findSendMode(String modeId) {
    if (modeId == null || modeId.isBlank()) {
      return null;
    }
    for (String id : enabledIds) {
      Mod mod = mods.get(id);
      if (mod != null) {
        SendMode mode = mod.sendMode();
        if (mode != null && modeId.equals(mode.id())) {
          return mode;
        }
      }
    }
    return null;
  }

  /** @return 全部已启用插件提供的发送模式（按名称排序，不含 null） */
  public List<SendMode> getSendModes() {
    List<SendMode> result = new ArrayList<>();
    for (String id : enabledIds) {
      Mod mod = mods.get(id);
      if (mod != null && mod.sendMode() != null) {
        result.add(mod.sendMode());
      }
    }
    result.sort(Comparator.comparing(SendMode::name));
    return Collections.unmodifiableList(result);
  }

  /**
   * 切换插件启用状态：启用触发 {@link Mod#onEnable(ModContext)}，禁用触发 {@link Mod#onDisable()}；未知 id 静默忽略。
   *
   * <p>幂等：重复启用/禁用不重复触发回调。副作用：插件自身的启用逻辑（可能 I/O 或网络，由插件实现决定）。
   *
   * @param id 插件 id
   * @param enabled true 启用，false 禁用
   */
  public void setEnabled(String id, boolean enabled) {
    Mod mod = mods.get(id);
    if (mod == null) {
      return;
    }
    if (enabled) {
      if (enabledIds.add(id)) {
        mod.onEnable(new ModContext(id, modsDir.getParent()));
        log.info("已启用插件: {} ({})", mod.name(), id);
      }
    } else {
      if (enabledIds.remove(id)) {
        mod.onDisable();
        log.info("已禁用插件: {} ({})", mod.name(), id);
      }
    }
  }

  /** 关闭并释放插件 classloader（不卸载已加载的类，仅释放 jar 文件句柄）。 */
  public void close() {
    closeClassLoader();
  }

  /** @return mods 目录下按文件名升序排列的 jar 文件列表；目录不存在返回空列表 */
  private List<Path> listJars() {
    if (!Files.isDirectory(modsDir)) {
      return Collections.emptyList();
    }
    try (var stream = Files.list(modsDir)) {
      return stream
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (IOException e) {
      log.warn("读取 mods 目录失败: {} ({})", modsDir, e.toString());
      return Collections.emptyList();
    }
  }

  private URLClassLoader newClassLoader(List<Path> jars) {
    URL[] urls = new URL[jars.size()];
    for (int i = 0; i < jars.size(); i++) {
      try {
        urls[i] = jars.get(i).toUri().toURL();
      } catch (IOException e) {
        // toURI().toURL() 对已存在的本地路径不会抛 IOException，兜底跳过
        urls[i] = null;
        log.warn("构造 jar URL 失败: {} ({})", jars.get(i), e.toString());
      }
    }
    List<URL> valid = new ArrayList<>();
    for (URL url : urls) {
      if (url != null) {
        valid.add(url);
      }
    }
    // 父类加载器为 Mod 接口所在加载器，保证插件实现与主程序共享同一 Mod 类
    return new URLClassLoader(valid.toArray(new URL[0]), Mod.class.getClassLoader());
  }

  private void closeClassLoader() {
    if (classLoader != null) {
      try {
        classLoader.close();
      } catch (IOException e) {
        log.warn("关闭插件 classloader 失败: {}", e.toString());
      }
      classLoader = null;
    }
  }

  /** @return mods 目录路径（供测试与日志展示） */
  Path getModsDir() {
    return modsDir;
  }
}
