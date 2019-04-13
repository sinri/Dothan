package io.github.sinri.Dothan.Security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

public class CryptAgentOfAES extends CryptAgent {
    private static SecretKey getSecretKey(String rawKey) throws NoSuchAlgorithmException {
        String[] keyAndIv = getKeyAndIv(rawKey);

        byte[] raw = keyAndIv[0].getBytes();
        //根据字节数组生成AES密钥
        return new SecretKeySpec(raw, "AES");

    }

    public static String encryptString(String content, String rawKey) {
        try {
            return Base64.getEncoder().encodeToString(encryptBytes(content.getBytes(StandardCharsets.UTF_8), rawKey));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] encryptBytes(byte[] contentBytes, String encryptKey) {
        try {
            SecretKey secretKey = getSecretKey(encryptKey);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return cipher.doFinal(contentBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decryptString(String content, String rawKey) {
        try {
            return new String(Objects.requireNonNull(decryptBytes(Base64.getDecoder().decode(content), rawKey)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] decryptBytes(byte[] encryptBytes, String decryptKey) {
        try {
            SecretKey secretKey = getSecretKey(decryptKey);
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            return (cipher.doFinal(encryptBytes));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            String token = "123";

            String source = "这是一段不可告人的秘密。";
            String encrypted = encryptString(source, token);
            String decrypted = decryptString(encrypted, token);
            System.out.println("source: " + source);
            System.out.println("encrypted: " + encrypted);
            System.out.println("decrypted: " + decrypted);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
