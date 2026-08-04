package ad.ovo.wol.exception;

/**
 * 业务异常：面向用户的 WOL 发送 / 配置操作失败。
 * <p>消息文本可直接展示到界面（不包含堆栈细节）。</p>
 */
public class WolException extends Exception {

    public WolException(String message) {
        super(message);
    }

    public WolException(String message, Throwable cause) {
        super(message, cause);
    }
}
