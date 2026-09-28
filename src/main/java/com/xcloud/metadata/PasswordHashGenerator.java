package com.xcloud.metadata;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordHashGenerator {

    public static void main(String[] args) {
        String password = "1122"; // 你指定的密码
        int iterations = 21000;   // 对应你项目代码中的迭代次数
        int saltLength = 16;      // 盐的长度，通常为 16 字节

        try {
            // 1. 随机生成盐 (Salt)
            byte[] salt = new byte[saltLength];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(salt);

            // 2. 使用 PBKDF2WithHmacSHA256 生成密钥
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256); // 256位密钥长度
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();

            // 3. 将盐值和哈希值转换为 Base64 字符串
            String base64Salt = Base64.getEncoder().encodeToString(salt);
            String base64Hash = Base64.getEncoder().encodeToString(hash);

            // 4. 按照项目代码的格式拼接输出 (迭代次数$盐$哈希值)
            String finalHash = iterations + "$" + base64Salt + "$" + base64Hash;

            System.out.println("========== 密码生成结果 ==========");
            System.out.println("原始密码: " + password);
            System.out.println("生成的哈希值: " + finalHash);
            System.out.println("==================================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}