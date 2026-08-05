package ad.ovo.wol;

import javafx.application.Application;

/**
 * 独立启动器（主类，不继承 Application）。
 *
 * <p>JDK 启动器与 jpackage 生成的原生启动器对「主类继承
 * javafx.application.Application」的应用存在内置检查：会经 FXHelper
 * 尝试从命名模块加载主类（classpath 应用不满足，报「缺少 JavaFX 运行时
 * 组件 / Missing JavaFX application class」）。主类改为普通类后走标准
 * main() 路径，在 main 内再启动 JavaFX 应用，兼容
 * {@code java -jar}、--module-path、jpackage 三种运行方式。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
