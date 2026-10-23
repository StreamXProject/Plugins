package javax.security.sasl;

/** IOException-compatible SASL failure; authentication itself remains in the MongoDB driver. */
public class SaslException extends java.io.IOException {
    public SaslException(){super();}
    public SaslException(String message){super(message);}
    public SaslException(String message,Throwable cause){super(message,cause);}
}
