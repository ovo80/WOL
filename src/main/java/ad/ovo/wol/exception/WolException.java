package ad.ovo.wol.exception;


public class WolException extends Exception {

    public WolException(String message) {
        super(message);
    }

    public WolException(String message, Throwable cause) {
        super(message, cause);
    }
}
