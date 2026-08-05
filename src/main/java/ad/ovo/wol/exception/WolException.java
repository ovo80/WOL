package ad.ovo.wol.exception;

/**
 * 业务异常：消息面向最终用户，可直接展示在界面状态区。
 *
 * <p>使用约定：Service 层将底层异常（{@link IllegalArgumentException}、 {@link
 * java.io.IOException}）转译为本类型后抛出；Controller 捕获后 直接展示 {@link #getMessage()}，不附加技术细节。
 */
public class WolException extends Exception {

  /**
   * @param message 用户可读的错误描述
   */
  public WolException(String message) {
    super(message);
  }

  /**
   * @param message 用户可读的错误描述
   * @param cause 原始异常，保留根因堆栈
   */
  public WolException(String message, Throwable cause) {
    super(message, cause);
  }
}
