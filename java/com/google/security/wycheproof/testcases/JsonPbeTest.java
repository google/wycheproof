/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.security.wycheproof;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for PBE.
 *
 * <p>The ciphers included in this test are ciphers that derive a symmetric key from a password,
 * salt and iteration count. The symmetric key is used for an encryption mode that requires an IV.
 * Password based encryption modes with a different set of parameters will use a different JSON
 * schema.
 *
 * @author bleichen@google.com (Daniel Bleichenbacher)
 */
@RunWith(JUnit4.class)
public class JsonPbeTest {

  /** Convenience method to get a byte array from a JsonObject. */
  private static byte[] getBytes(JsonObject object, String name) {
    return JsonUtil.asByteArray(object.get(name));
  }

  /**
   * Tries to convert password into PBEKeySpec.
   *
   * <p>One issue here is that PBEKeySpec requires a char[] as parameter. The key derivation
   * converts the passowrd in the PBEKeySpec back to a byte[]. This conversion is not well defined.
   * E.g., when a PBEKeySpec is used for PBKDF then the password is converted using UTF-8. PBE
   * implementations sometimes add additional restrictions. For example the SUNJCE provider requires
   * that passwords contain only printable ASCII characters.
   *
   * @param password the password to convert
   * @return the password as a PBEKeySpec
   * @throws InvalidKeyException if password cannot be converted to a char[].
   */
  private static PBEKeySpec convertPassword(byte[] password) throws InvalidKeyException {
    CharsetDecoder decoder = UTF_8.newDecoder();
    CharBuffer buffer;
    try {
      buffer = decoder.decode(ByteBuffer.wrap(password));
    } catch (CharacterCodingException ex) {
      throw new InvalidKeyException("Only UTF-8 encoded passwords are supported");
    }
    char[] pwd = new char[buffer.limit()];
    buffer.get(pwd);
    return new PBEKeySpec(pwd);
  }

  /**
   * Derives a key and returns an initialized instance of Cipher.
   *
   * @param algorithm the name of an algorithm (e.g., "PbeWithHmacSha1AndAes_128")
   * @param keySpec a PBEKeySpec containing the password.
   * @param salt the salt for the key derivation function
   * @param iterCount the number of iterations done by the key derivation function
   * @param opmode Cipher.ENCRYPT_MODE for encryption or Cipher.DECRYPT_MODE for decryption
   * @param iv the iv of the symmetric cipher (e.g., must be 16 bytes if AES-CBC is being used).
   * @return an inintialized instance of Cipher
   * @throws GeneralSecurityException if the cipher could not be constructed
   */
  private static Cipher getInitializedCipher(
      String algorithm, PBEKeySpec keySpec, byte[] salt, int iterCount, int opmode, byte[] iv)
      throws GeneralSecurityException {
    Cipher pbe = Cipher.getInstance(algorithm);
    // So far I haven't found a method to compute PBES2 in a provider independent way.
    // The method used here is from TestCipherKeyWrapperTest.java.
    // It only works for OpenJdk, but no other provider.
    // Conscrypt appears to require that a SecretKeyFactory from another provider
    // is present.
    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(algorithm);
    // If the SUNJCE provider is used then pbeKey is an instance of com.sun.crypto.provider.PBEKey.
    // The class PBEKey adds additional restrictions to valid passwords: it only accepts
    // passwords consisting of printable ASCII characters.
    SecretKey pbeKey = keyFactory.generateSecret(keySpec);
    IvParameterSpec ivParam = new IvParameterSpec(iv);
    PBEParameterSpec params = new PBEParameterSpec(salt, iterCount, ivParam);
    pbe.init(opmode, pbeKey, params);
    return pbe;
  }

  /**
   * Example format for test vectors
   *
   * <pre>
   * {
   *  "algorithm" : "PbeWithHmacSha1AndAes_128",
   *  "schema" : "pbe_test_schema.json",
   *  "generatorVersion" : "0.9",
   *  "numberOfTests" : 68,
   *  "header" : [
   *    "Test vector of type PbeTest are used for PBES1 or PBES2."
   *  ],
   *  "notes" : {
   *    "Ascii" : {
   *      "bugType" : "FUNCTIONALITY",
   *      "description" : "The test vector contains a password consisting of ASCII characters."
   *    },
   *    ...
   *    }
   *  },
   *  "testGroups" : [
   *    {
   *      "type" : "PbeTest",
   *      "tests" : [
   *        {
   *          "tcId" : 1,
   *          "comment" : "",
   *          "flags" : [
   *            "Printable"
   *          ],
   *          "password" : "344b6769305a6e72",
   *          "salt" : "fcd9a324f025ef40",
   *          "iterationCount" : 4096,
   *          "iv" : "42f02ff71b8524d1678ab2e34f9e7d47",
   *          "msg" : "",
   *          "ct" : "657976042ceac9615f32b5d43182efc4",
   *          "result" : "valid"
   *        },
   *       ...
   * </pre>
   */
  private static void singleTest(String algorithm, JsonObject testcase, TestResult testResult) {
    int tcId = testcase.get("tcId").getAsInt();
    byte[] password = getBytes(testcase, "password");
    byte[] salt = getBytes(testcase, "salt");
    int iterationCount = testcase.get("iterationCount").getAsInt();
    byte[] iv = getBytes(testcase, "iv");
    byte[] msg = getBytes(testcase, "msg");
    byte[] ciphertext = getBytes(testcase, "ct");
    // Result is one of "valid", "invalid", "acceptable".
    // "valid" are test vectors with matching plaintext, ciphertext and tag.
    // "invalid" are test vectors with invalid parameters or invalid ciphertext and tag.
    // "acceptable" are test vectors with weak parameters or legacy formats.
    String result = testcase.get("result").getAsString();
    PBEKeySpec pbeKey;
    try {
      pbeKey = convertPassword(password);
    } catch (InvalidKeyException ex) {
      testResult.addResult(tcId, TestResult.Type.REJECTED_ALGORITHM, ex.toString());
      return;
    }

    Cipher pbe;
    try {
      pbe = getInitializedCipher(algorithm, pbeKey, salt, iterationCount, Cipher.ENCRYPT_MODE, iv);
    } catch (GeneralSecurityException ex) {
      // Some libraries restrict valid characters in the key or may restrict other parameters.
      // Because of this the initialization of the cipher might fail. Hence the test will be
      // skipped.
      testResult.addResult(tcId, TestResult.Type.REJECTED_ALGORITHM, ex.toString());
      return;
    }
    TestResult.Type resultType;
    String comment = "";
    // Normally the test tries to encrypt and decrypt a ciphertext.
    // tryDecrypt is set to false if a bug during encryption was serious enough,
    // so that trying to decrypt no longer makes sense.
    boolean tryDecrypt = true;
    try {
      byte[] encrypted = pbe.doFinal(msg);
      boolean eq = Arrays.equals(ciphertext, encrypted);
      if (result.equals("invalid")) {
        if (eq) {
          // Some test vectors use invalid parameters that should be rejected.
          resultType = TestResult.Type.NOT_REJECTED_INVALID;
          tryDecrypt = false;
        } else {
          // Invalid test vectors frequently have invalid paddings.
          // Hence encryption just gives a different result.
          resultType = TestResult.Type.REJECTED_INVALID;
        }
      } else {
        if (!eq) {
          // If encryption returns the wrong result then something is
          // broken. Hence we can stop here.
          resultType = TestResult.Type.WRONG_RESULT;
          comment = "ciphertext: " + TestUtil.bytesToHex(encrypted);
          tryDecrypt = false;
        } else {
          resultType = TestResult.Type.PASSED_VALID;
        }
      }
    } catch (GeneralSecurityException ex) {
      if (result.equals("valid")) {
        resultType = TestResult.Type.REJECTED_VALID;
      } else {
        resultType = TestResult.Type.REJECTED_INVALID;
      }
    }

    if (tryDecrypt) {
      // Test decryption
      try {
        pbe =
            getInitializedCipher(algorithm, pbeKey, salt, iterationCount, Cipher.DECRYPT_MODE, iv);
        byte[] decrypted = pbe.doFinal(ciphertext);
        boolean eq = Arrays.equals(decrypted, msg);
        if (result.equals("invalid")) {
          resultType = TestResult.Type.NOT_REJECTED_INVALID;
        } else if (!eq) {
          resultType = TestResult.Type.WRONG_RESULT;
          comment = "decrypted:" + TestUtil.bytesToHex(decrypted);
        } else {
          resultType = TestResult.Type.PASSED_VALID;
        }
      } catch (GeneralSecurityException ex) {
        comment = ex.toString();
        if (result.equals("valid")) {
          resultType = TestResult.Type.REJECTED_VALID;
        } else {
          resultType = TestResult.Type.REJECTED_INVALID;
        }
      }
    }
    testResult.addResult(tcId, resultType, comment);
  }

  /**
   * Checks each test vector in a file of test vectors.
   *
   * <p>One motivation for running all the test vectors in a file at once is that this allows us to
   * test if invalid paddings result in distinguishable exceptions. Throwing distinguishable
   * exceptions can contain information that helps an attacker in a chosen ciphertext attack.
   *
   * @param testVectors the test vectors
   * @return a test result
   */
  public static TestResult allTests(TestVectors testVectors) {
    var testResult = new TestResult(testVectors);
    JsonObject test = testVectors.getTest();
    String algorithm = test.get("algorithm").getAsString();
    try {
      Cipher.getInstance(algorithm);
    } catch (GeneralSecurityException ex) {
      // We might try to find alternative algorithm names here.
      // For example, BouncyCastle implements algorithms such as
      // PBEWITHSHAAND128BITAES-CBC-BC
      // However, these algorithms use PKCS #12 conversion from passwords
      // to bytes. This conversion uses 2 bytes for each character.
      // Hence the algorithm is not compatible with the SUNJCE version.
      testResult.addFailure(TestResult.Type.REJECTED_ALGORITHM, algorithm);
      return testResult;
    }
    for (JsonElement g : test.getAsJsonArray("testGroups")) {
      JsonObject group = g.getAsJsonObject();
      for (JsonElement t : group.getAsJsonArray("tests")) {
        JsonObject testcase = t.getAsJsonObject();
        singleTest(algorithm, testcase, testResult);
      }
    }
    // Test vectors with invalid padding must have indistinguishable behavior.
    // The test here checks for distinct exceptions. There are other ways to
    // distinguish paddings, such as timing differences. Such differences are
    // not checked here.
    testResult.checkIndistinguishableResult("BadPadding");
    return testResult;
  }

  /**
   * Tests a PBE ciphers against test vectors.
   *
   * @param filename the JSON file with the test vectors.
   * @throws AssumptionViolatedException when the test was skipped. This happens for example when
   *     the underlying cipher or padding method is not supported. It is also possible that a test
   *     is skipped if the provider uses non-standard algorithm names.
   * @throws AssertionError when the test failed.
   * @throws IOException when the test vectors could not be read.
   */
  public void testPbe(String filename) throws IOException {
    JsonObject test = JsonUtil.getTestVectorsV1(filename);
    TestVectors testVectors = new TestVectors(test, filename);
    TestResult testResult = allTests(testVectors);

    if (testResult.skipTest()) {
      System.out.println("Skipping " + filename + " no ciphertext decrypted.");
      TestUtil.skipTest("No ciphertext decrypted");
      return;
    }
    System.out.print(testResult.asString());
    assertEquals(0, testResult.errors());
  }

  @Test
  public void testPbes2Hmacsha1Aes128() throws Exception {
    testPbe("pbes2_hmacsha1_aes_128_test.json");
  }

  @Test
  public void testPbes2Hmacsha1Aes192() throws Exception {
    testPbe("pbes2_hmacsha1_aes_192_test.json");
  }

  @Test
  public void testPbes2Hmacsha1Aes256() throws Exception {
    testPbe("pbes2_hmacsha1_aes_256_test.json");
  }

  @Test
  public void testPbes2Hmacsha224Aes128() throws Exception {
    testPbe("pbes2_hmacsha224_aes_128_test.json");
  }

  @Test
  public void testPbes2Hmacsha224Aes192() throws Exception {
    testPbe("pbes2_hmacsha224_aes_192_test.json");
  }

  @Test
  public void testPbes2Hmacsha224Aes256() throws Exception {
    testPbe("pbes2_hmacsha224_aes_256_test.json");
  }

  @Test
  public void testPbes2Hmacsha256Aes128() throws Exception {
    testPbe("pbes2_hmacsha256_aes_128_test.json");
  }

  @Test
  public void testPbes2Hmacsha256Aes192() throws Exception {
    testPbe("pbes2_hmacsha256_aes_192_test.json");
  }

  @Test
  public void testPbes2Hmacsha256Aes256() throws Exception {
    testPbe("pbes2_hmacsha256_aes_256_test.json");
  }

  @Test
  public void testPbes2Hmacsha384Aes128() throws Exception {
    testPbe("pbes2_hmacsha384_aes_128_test.json");
  }

  @Test
  public void testPbes2Hmacsha384Aes192() throws Exception {
    testPbe("pbes2_hmacsha384_aes_192_test.json");
  }

  @Test
  public void testPbes2Hmacsha384Aes256() throws Exception {
    testPbe("pbes2_hmacsha384_aes_256_test.json");
  }

  @Test
  public void testPbes2Hmacsha512Aes128() throws Exception {
    testPbe("pbes2_hmacsha512_aes_128_test.json");
  }

  @Test
  public void testPbes2Hmacsha512Aes192() throws Exception {
    testPbe("pbes2_hmacsha512_aes_192_test.json");
  }

  @Test
  public void testPbes2Hmacsha512Aes256() throws Exception {
    testPbe("pbes2_hmacsha512_aes_256_test.json");
  }
}
