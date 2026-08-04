package ad.ovo.wol;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FXML + CSS 加载测试（无 JUnit 依赖，直接 java 运行）：
 * 验证 main.fxml 语法、fx:controller 绑定、fx:id 与 MainController 字段匹配、
 * initialize() 正常执行，以及双主题 CSS 可被 JavaFX 解析（语法错误会打印到 stderr）。
 * 需要真实桌面环境（JavaFX Toolkit）。
 *
 * <p>运行（需桌面会话）：</p>
 * <pre>{@code java -cp "target/classes;target/test-classes;target/lib/*" ad.ovo.wol.FxmlCheck}</pre>
 */
public class FxmlCheck {

    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> info = new AtomicReference<>();

        Platform.startup(() -> { });
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        FxmlCheck.class.getResource("/ad/ovo/wol/main.fxml"));
                Parent root = loader.load();

                // 挂载 Scene 并加载双主题 CSS，触发样式计算以暴露 CSS 语法错误
                Scene scene = new Scene(root);
                scene.getStylesheets().add(FxmlCheck.class
                        .getResource("/ad/ovo/wol/css/theme-dark.css").toExternalForm());
                scene.getStylesheets().add(FxmlCheck.class
                        .getResource("/ad/ovo/wol/css/theme-light.css").toExternalForm());
                root.applyCss();

                info.set("[OK] 根节点: " + root.getClass().getSimpleName()
                        + " | 控制器: " + loader.getController().getClass().getName()
                        + " | initialize() 已执行 | 双主题 CSS 已解析");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        Platform.exit();

        if (error.get() != null) {
            System.out.println("[FAIL] FXML 加载失败: " + error.get());
            error.get().printStackTrace();
            System.exit(1);
        }
        System.out.println("[OK] FXML 加载成功");
        System.out.println(info.get());
        System.out.println("FXML CHECK PASSED（若上方 stderr 出现 CSS Error 即存在样式语法问题）");
    }
}
