package app.wisprail.engine;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

/** Per-process API server identity prevents a local listener from impersonating the engine. */
record EngineCredentials(String token, String certificate, String key) {
  private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();

  static EngineCredentials create()
      throws GeneralSecurityException, IOException, OperatorCreationException {
    SecureRandom random = new SecureRandom();
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, random);
    var pair = generator.generateKeyPair();
    Instant now = Instant.now();
    X500Name name = new X500Name("CN=Wisprail local engine");
    var certificate =
        new JcaX509v3CertificateBuilder(
            name,
            new BigInteger(160, random),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(86400)),
            name,
            pair.getPublic());
    certificate.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
    var signed =
        certificate.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
    return new EngineCredentials(
        Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
        pem(signed),
        pem(pair.getPrivate()));
  }

  static String decryptPrivateKey(String content, String password)
      throws IOException, OperatorCreationException, PKCSException {
    if (content.isBlank() || !content.contains("ENCRYPTED")) {
      return content;
    }
    try (PEMParser parser = new PEMParser(new StringReader(content))) {
      Object parsed = parser.readObject();
      PrivateKeyInfo key;
      if (parsed instanceof PEMEncryptedKeyPair encrypted) {
        key =
            encrypted
                .decryptKeyPair(
                    new JcePEMDecryptorProviderBuilder()
                        .setProvider(PROVIDER)
                        .build(password.toCharArray()))
                .getPrivateKeyInfo();
      } else if (parsed instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
        key =
            encrypted.decryptPrivateKeyInfo(
                new JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(PROVIDER)
                    .build(password.toCharArray()));
      } else if (parsed instanceof PEMKeyPair pair) {
        key = pair.getPrivateKeyInfo();
      } else {
        throw new IOException("Неподдерживаемый формат закрытого ключа");
      }
      return pem(key);
    }
  }

  private static String pem(Object object) throws IOException {
    StringWriter text = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
      writer.writeObject(object);
    }
    return text.toString();
  }

  @Override
  public String toString() {
    return "EngineCredentials[REDACTED]";
  }
}
