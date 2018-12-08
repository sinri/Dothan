package Security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class CryptAgentOfAES extends CryptAgent {
    private static SecretKey getSecretKey(String rawKey) throws NoSuchAlgorithmException {
        String[] keyAndIv = getKeyAndIv(rawKey);

        byte[] raw = keyAndIv[0].getBytes();
        //根据字节数组生成AES密钥
        return new SecretKeySpec(raw, "AES");

    }

    /**
     * 加密
     * 1.构造密钥生成器
     * 2.根据ecnodeRules规则初始化密钥生成器
     * 3.产生密钥
     * 4.创建和初始化密码器
     * 5.内容加密
     * 6.返回字符串
     */
    public static String encryptString(String content, String rawKey) {
        try {
            return Base64.getEncoder().encodeToString(encryptBytes(content.getBytes(StandardCharsets.UTF_8), rawKey));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * AES加密
     *
     * @param contentBytes 待加密的内容
     * @param encryptKey   加密密钥
     * @return 加密后的byte[]
     */
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

    /**
     * 解密
     * 解密过程：
     * 1.同加密1-4步
     * 2.将加密后的字符串反纺成byte[]数组
     * 3.将加密内容解密
     */
    public static String decryptString(String content, String rawKey) {
        try {
            return new String(decryptBytes(Base64.getDecoder().decode(content), rawKey), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * AES解密
     *
     * @param encryptBytes 待解密的byte[]
     * @param decryptKey   解密密钥
     * @return 解密后的String
     */
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
