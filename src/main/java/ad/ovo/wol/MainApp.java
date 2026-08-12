/*
 * WOL 唤醒工具 - JavaFX 应用入口与主窗口装配。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import ad.ovo.wol.common.config.AppConfig;
import ad.ovo.wol.service.ConfigService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX 应用入口：装配主窗口与主题。
 *
 * <p>职责边界：加载 {@code main.fxml} 与主题 CSS、设置窗口标题/图标/固定尺寸；界面交互委托给 {@link MainController}。
 *
 * <p>线程约束：{@link #start(Stage)} 在 JavaFX 应用线程执行；启动失败时弹出模态错误框并调用 {@link Platform#exit()} 终止进程。
 */
public class MainApp extends Application {

  private static final Logger log = LoggerFactory.getLogger(MainApp.class);

  /**
   * JavaFX 生命周期入口，装配场景并显示主窗口。
   *
   * @param primaryStage JavaFX 平台创建的根窗口，直接在此装配
   * @throws Exception FXML 或 CSS 资源缺失/解析失败时抛出，由 {@code launch()} 捕获并终止应用（启动即失败，无降级路径）
   */
  @Override
  public void start(Stage primaryStage) {
    try {
      // 资源缺失时此处抛出异常，进入下方统一失败处理
      Parent root = new FXMLLoader(getClass().getResource("/ad/ovo/wol/main.fxml")).load();

      Scene scene = new Scene(root);
      // theme 取值仅 dark / light（非法值已在 AppSettings#setTheme 收敛为 dark）
      String theme = ConfigService.loadSettings().getTheme();
      scene
          .getStylesheets()
          .add(getClass().getResource("/ad/ovo/wol/css/theme-" + theme + ".css").toExternalForm());

      primaryStage.setTitle(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);

      try {
        primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png")));
      } catch (Exception e) {
        // 图标缺失不阻断启动，仅记录警告
        log.warn("应用图标加载失败，使用默认图标", e);
      }
      primaryStage.setScene(scene);

      primaryStage.setResizable(false);
      primaryStage.show();
      log.info("{} v{} 启动完成", AppConfig.APP_NAME, AppConfig.APP_VERSION);
    } catch (Exception e) {
      log.error("应用启动失败", e);
      Alert alert = new Alert(Alert.AlertType.ERROR);
      alert.setTitle("启动失败");
      alert.setHeaderText("应用初始化失败");
      alert.setContentText("错误详情：" + e.getMessage() + "\n请查看日志文件获取更多信息。");
      alert.showAndWait();
      Platform.exit();
    }
  }

  /** 供 IDE 直接运行本类的入口（与 {@link Launcher#main} 等效）。 */
  public static void main(String[] args) {
    launch(args);
  }
}
