/*
 * WOL 唤醒工具 - FXML 与双主题 CSS 加载测试。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ad.ovo.modloader.PluginManager;
import ad.ovo.wol.controller.MainController;
import ad.ovo.wol.model.Device;
import ad.ovo.wol.model.DeviceConfig;
import ad.ovo.wol.plugin.LanguageManager;
import ad.ovo.wol.plugin.SendMode;
import ad.ovo.wol.plugin.ThemeManager;
import ad.ovo.wol.service.ConfigService;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FXML 与双主题 CSS 加载测试（需桌面会话）。
 *
 * <p>断言控制器绑定正确（{@link MainController}）、设置按钮已装配（齿轮图案），并验证 settings.fxml 可加载。
 */
class FxmlLoadTest {

  @TempDir Path tempDir;

  /** 启动 JavaFX 平台（整个测试类仅一次）。 */
  @BeforeAll
  static void startJavaFx() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Platform.startup(latch::countDown);
    await(latch);
  }

  @AfterAll
  static void stopJavaFx() {
    Platform.exit();
  }

  @BeforeEach
  void isolateConfigDir() {
    System.setProperty("wol.config.dir", tempDir.resolve("config").toString());
  }

  @Test
  void fxml加载成功且双主题CSS可解析() throws Exception {
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicReference<Parent> rootRef = new AtomicReference<>();
    AtomicReference<Object> controller = new AtomicReference<>();
    AtomicReference<String> settingsButtonText = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          try {
            FXMLLoader loader =
                new FXMLLoader(FxmlLoadTest.class.getResource("/ad/ovo/wol/main.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root);
            scene
                .getStylesheets()
                .add(
                    FxmlLoadTest.class
                        .getResource("/ad/ovo/wol/css/theme-dark.css")
                        .toExternalForm());
            scene
                .getStylesheets()
                .add(
                    FxmlLoadTest.class
                        .getResource("/ad/ovo/wol/css/theme-light.css")
                        .toExternalForm());
            root.applyCss();
            rootRef.set(root);
            controller.set(loader.getController());
            javafx.scene.control.Button settingsButton =
                (javafx.scene.control.Button) root.lookup("#settingsButton");
            settingsButtonText.set(settingsButton == null ? null : settingsButton.getText());
          } catch (Throwable t) {
            error.set(t);
          } finally {
            latch.countDown();
          }
        });

    await(latch);
    assertNull(error.get(), "FXML 加载不应失败");
    assertNotNull(rootRef.get());
    assertEquals(MainController.class, controller.get().getClass());
    assertNotNull(settingsButtonText.get(), "设置按钮应已装配");
    assertTrue(!settingsButtonText.get().isBlank(), "设置按钮应有齿轮图案");
  }

  @Test
  void settingsFxml可加载() throws Exception {
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          try {
            FXMLLoader loader =
                new FXMLLoader(FxmlLoadTest.class.getResource("/ad/ovo/wol/settings.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root);
            scene
                .getStylesheets()
                .add(
                    FxmlLoadTest.class
                        .getResource("/ad/ovo/wol/css/theme-dark.css")
                        .toExternalForm());
            root.applyCss();
          } catch (Throwable t) {
            error.set(t);
          } finally {
            latch.countDown();
          }
        });

    await(latch);
    assertNull(error.get(), "settings.fxml 加载不应失败");
  }

  @SuppressWarnings("unchecked") // FXML lookup 返回原始 ComboBox，此处强转类型安全
  @Test
  void 插件注入后重选当前设备模式且不标脏() throws Exception {
    // 预置一台 test-mode 设备（initialize 阶段插件未注入，selectMode 会回退选中「普通广播」）
    Device device = new Device();
    device.setName("mode-device");
    device.setMacAddress("00:1A:2B:3C:4D:5E");
    device.setBroadcastAddress("10.0.0.255");
    device.setPort(9);
    device.setMode("test-mode");
    device.setModeValue("dummy");
    DeviceConfig config = new DeviceConfig();
    config.setDevices(List.of(device));
    ConfigService.save(config);

    // mods 目录放测试插件 jar 并启用（提供 id=test-mode 的发送模式）
    Path configDir = tempDir.resolve("config");
    Files.createDirectories(configDir.resolve("mods"));
    writeModJar(configDir.resolve("mods").resolve("test-mod.jar"));
    PluginManager pluginManager = new PluginManager(configDir);
    pluginManager.scan();
    pluginManager.setEnabled("com.example.test", true);
    ThemeManager themeManager = new ThemeManager(configDir);
    themeManager.scan();
    LanguageManager languageManager = new LanguageManager(configDir);
    languageManager.scan();

    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicReference<MainController> controllerRef = new AtomicReference<>();
    AtomicReference<ComboBox<SendMode>> modeBoxRef = new AtomicReference<>();
    AtomicReference<TextField> portFieldRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          try {
            FXMLLoader loader =
                new FXMLLoader(FxmlLoadTest.class.getResource("/ad/ovo/wol/main.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            controller.injectPluginServices(themeManager, languageManager, pluginManager);
            controllerRef.set(controller);
            modeBoxRef.set((ComboBox<SendMode>) root.lookup("#modeBox"));
            portFieldRef.set((TextField) root.lookup("#portField"));
          } catch (Throwable t) {
            error.set(t);
          } finally {
            latch.countDown();
          }
        });

    await(latch);
    assertNull(error.get(), "加载与注入不应失败");
    try {
      SendMode selected = modeBoxRef.get().getSelectionModel().getSelectedItem();
      assertNotNull(selected, "注入后应重选设备持久化的发送模式而非回退「普通广播」");
      assertEquals("test-mode", selected.id());
      assertTrue(portFieldRef.get().isDisable(), "test-mode 不使用端口字段，端口框应禁用");

      // 程序化重填下拉（refreshModeOptions 清空选中）不应把用户未保存状态误标为 dirty
      Field dirtyField = MainController.class.getDeclaredField("dirty");
      dirtyField.setAccessible(true);
      assertFalse(
          (Boolean) dirtyField.get(controllerRef.get()),
          "注入后的程序化重选不应标记未保存修改");
    } finally {
      // 释放 mods jar 的 URLClassLoader 句柄，否则 Windows 上 TempDir 清理会失败
      pluginManager.close();
    }
  }

  /** 把 TestMod/TestSendMode 编译产物与 SPI 注册文件打包进 jar（与加载器仓库的端到端机制相同）。 */
  private void writeModJar(Path jarPath) throws IOException {
    try (OutputStream out = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(out)) {
      jar.putNextEntry(new JarEntry("META-INF/services/ad.ovo.modloader.Mod"));
      jar.write("ad.ovo.wol.TestMod\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      jar.closeEntry();

      for (String classResource : new String[] {"/ad/ovo/wol/TestMod.class", "/ad/ovo/wol/TestSendMode.class"}) {
        try (var in = FxmlLoadTest.class.getResourceAsStream(classResource)) {
          if (in == null) {
            throw new IOException("测试实现类未编译: " + classResource);
          }
          jar.putNextEntry(new JarEntry(classResource.substring(1)));
          in.transferTo(jar);
          jar.closeEntry();
        }
      }
    }
  }

  /** 阻塞等待闩锁，超时 15s 抛异常（避免测试悬挂）。 */
  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("JavaFX 平台启动超时");
    }
  }
}
