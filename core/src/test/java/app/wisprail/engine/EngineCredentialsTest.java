package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPairGenerator;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.pkcs.PKCSException;
import org.junit.jupiter.api.Test;

class EngineCredentialsTest {
  @Test
  void decryptsPasswordProtectedPkcs8AndRejectsIncorrectPassword() throws Exception {
    var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    var encryptor =
        new JceOpenSSLPKCS8EncryptorBuilder(JcaPKCS8Generator.AES_256_CBC)
            .setProvider(new BouncyCastleProvider())
            .setPassword("test-passphrase".toCharArray())
            .build();
    StringWriter content = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(content)) {
      writer.writeObject(new JcaPKCS8Generator(pair.getPrivate(), encryptor));
    }
    String clear = EngineCredentials.decryptPrivateKey(content.toString(), "test-passphrase");
    try (PEMParser parser = new PEMParser(new StringReader(clear))) {
      Object decoded = parser.readObject();
      PrivateKeyInfo decodedKey =
          decoded instanceof PEMKeyPair pemPair
              ? pemPair.getPrivateKeyInfo()
              : PrivateKeyInfo.getInstance(decoded);
      assertEquals(PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded()), decodedKey);
    }
    assertThrows(
        PKCSException.class,
        () -> EngineCredentials.decryptPrivateKey(content.toString(), "incorrect"));
  }
}
