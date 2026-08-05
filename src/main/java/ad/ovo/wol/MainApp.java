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


public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/ad/ovo/wol/main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);

        String theme = new WolService().loadConfig().getTheme();
        scene.getStylesheets().add(getClass()
                .getResource("/ad/ovo/wol/css/theme-" + theme + ".css").toExternalForm());

        primaryStage.setTitle(AppConfig.APP_NAME + " v" + AppConfig.APP_VERSION);

        try {
            primaryStage.getIcons().add(new Image(
                    getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            log.warn("应用图标加载失败，使用默认图标", e);
        }
        primaryStage.setScene(scene);


        primaryStage.setResizable(false);
        primaryStage.show();
        log.info("{} v{} 启动完成", AppConfig.APP_NAME, AppConfig.APP_VERSION);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
