package ad.ovo.wol;

import ad.ovo.wol.config.AppConfig;
import ad.ovo.wol.service.WolService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 程序入口（MVC - View 装配层）：加载 FXML、应用主题并展示主窗口。
 *
 * <p>启动方式：{@code mvn javafx:run}，或打包后 {@code java -jar wol-1.1.0.jar}（依赖位于 lib/）。</p>
 */
public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/ad/ovo/wol/main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);
        // 初始主题：从持久化配置读取（统一经 Service 层，与 Controller 保持同一访问路径）
        String theme = new WolService().loadConfig().getTheme();
        scene.getStylesheets().add(getClass()
                .getResource("/ad/ovo/wol/css/theme-" + theme + ".css").toExternalForm());

        primaryStage.setTitle(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);
        // 窗口左上角图标（打包进 JAR 的 icon.png；加载失败不影响启动）
        try {
            primaryStage.getIcons().add(new Image(
                    getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            log.warn("应用图标加载失败，使用默认图标", e);
        }
        primaryStage.setScene(scene);
        // 固定窗口：禁用调整大小、最大化按钮、双击标题栏最大化 / Win+↑ / 拖拽全屏，
        // 窗口尺寸由 FXML root 的 prefWidth / prefHeight 决定
        primaryStage.setResizable(false);
        primaryStage.show();
        log.info("{} v{} 启动完成", AppConfig.APP_NAME, AppConfig.APP_VERSION);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
