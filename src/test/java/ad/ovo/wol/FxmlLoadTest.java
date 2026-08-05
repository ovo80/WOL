package ad.ovo.wol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FXML 与双主题 CSS 加载测试（需桌面会话）。
 *
 * <p>在 JavaFX 平台线程加载 {@code main.fxml} 并应用双主题样式表，断言： 控制器绑定正确（{@link MainController}）、主题按钮在
 * initialize 阶段已同步 初始图案——防止 {@link MainController#applyTheme(String)} 调用顺序回退 导致按钮无图案的回归。
 */
class FxmlLoadTest {

  @TempDir Path tempDir;

  /**
   * 启动 JavaFX 平台（整个测试类仅一次）。
   *
   * @throws Exception 平台启动超时（15s）时
   */
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
    AtomicReference<String> themeButtonText = new AtomicReference<>();
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
            javafx.scene.control.Button themeButton =
                (javafx.scene.control.Button) root.lookup("#themeButton");
            themeButtonText.set(themeButton == null ? null : themeButton.getText());
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
    assertNotNull(themeButtonText.get(), "主题按钮启动时应有初始图案");
    assertTrue(!themeButtonText.get().isBlank(), "主题按钮启动时应有初始图案");
  }

  /**
   * 阻塞等待闩锁，超时 15s 抛异常（避免测试悬挂）。
   *
   * @param latch 待等待的闩锁
   * @throws InterruptedException 等待被中断时
   * @throws IllegalStateException 超时未释放时
   */
  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("JavaFX 平台启动超时");
    }
  }
}
