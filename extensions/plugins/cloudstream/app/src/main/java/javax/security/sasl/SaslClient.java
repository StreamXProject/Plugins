package javax.security.sasl;

/** Java SASL API contract needed by MongoDB's own SCRAM implementation on Android. */
public interface SaslClient {
    String getMechanismName();
    boolean hasInitialResponse();
    byte[] evaluateChallenge(byte[] challenge) throws SaslException;
    boolean isComplete();
    byte[] unwrap(byte[] incoming,int offset,int length) throws SaslException;
    byte[] wrap(byte[] outgoing,int offset,int length) throws SaslException;
    Object getNegotiatedProperty(String property);
    void dispose() throws SaslException;
}
