/*
 * WOL 唤醒工具 - 程序入口。
 *
 * Copyright (c) 2026 ovo80
 * MIT License. See the LICENSE file in the project root for details.
 */
package ad.ovo.wol;

import javafx.application.Application;

/**
 * 程序入口：以普通 {@code main} 启动 JavaFX 应用。
 *
 * <p>JavaFX 启动器对「主类直接继承 {@code javafx.application.Application}」存在模块检查（要求 javafx.graphics 位于 {@code
 * --module-path}）。本类不继承 Application，内部调用 {@link Application#launch(Class, String...)}， 使应用兼容 {@code
 * java -jar}、{@code --module-path} 与 jpackage 三种启动方式。
 *
 * <p>线程约束：阻塞当前线程直至 JavaFX 窗口关闭；无全局状态修改。
 */
public final class Launcher {

  private Launcher() {}

  /**
   * 启动 {@link MainApp}。
   *
   * @param args 启动参数（当前未使用，原样透传）
   */
  public static void main(String[] args) {
    Application.launch(MainApp.class, args);
  }
}
