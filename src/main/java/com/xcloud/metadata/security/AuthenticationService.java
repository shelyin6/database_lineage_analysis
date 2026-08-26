package com.xcloud.metadata.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private static final String DUMMY_PASSWORD_HASH =
            "210000$4DKoHrnMNwVI8NMoMkXYuw==$kz5eqJoWsiqDgv53idnM3yOU60tsUSv2/zIUQY6FtVM=";
    private static final Map<String, Account> ACCOUNTS = Map.of(
            "admin", new Account("admin", "系统管理员", "ADMIN",
                    "210000$9KxcFcaa988pqIyKhsjruw==$vZb4HCHI7QtCQ2kjcx6YCK2PtW86rLJ8CUfk1kodM6E="),
            "almp", new Account("almp", "ALMP 分析员", "USER",
                    "210000$lntvp6LSGI7bF23w8oFedA==$2cs6PFGNre7gSxTV4z+zZVfIJdCksJWj+34d7dIyhbk="),
            "ids", new Account("ids", "IDS 分析员", "USER",
                    "210000$BUYLaJS+98GUR7BwJAX13w==$kgB7B5I4kY8LLbCfSxCfZT+9gnFJUK0qP4Bc6RYwpRM="),
            "analyst", new Account("analyst", "数据分析员", "USER",
                    "210000$nC51yU02E+Fhyxdoo1ugYQ==$AMMoi7HM/PmX+dQKI/SnS5iXoFlihk0LjApOjW6L24M="),
            "operator", new Account("operator", "运行操作员", "USER",
                    "210000$GH8mAeqcU5FVXWao6+CUNw==$dZbpOoW9cE65vTxc8skYxVExiAJ18BB8f1LfQZb5LFU="),
            "viewer", new Account("viewer", "数据查看员", "USER",
                    "210000$NHvd2yQImXxO7RUKHvSogg==$vmcN1VjIRn3RqXCLY/6atVgVzo048G7WdmB7h9PFe38=")
    );

    public AuthUser authenticate(String username, String password) {
        Account account = ACCOUNTS.get(normalizeUsername(username));
        String passwordHash = account == null ? DUMMY_PASSWORD_HASH : account.passwordHash();
        boolean matches = password != null && matches(password, passwordHash);
        return matches && account != null
                ? new AuthUser(account.username(), account.displayName(), account.role())
                : null;
    }

    public String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String password, String encoded) {
        String[] values = encoded.split("\\$", -1);
        if (values.length != 3) {
            return false;
        }

        PBEKeySpec specification = null;
        try {
            int iterations = Integer.parseInt(values[0]);
            byte[] salt = Base64.getDecoder().decode(values[1]);
            byte[] expected = Base64.getDecoder().decode(values[2]);
            specification = new PBEKeySpec(password.toCharArray(), salt, iterations, expected.length * 8);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return false;
        } finally {
            if (specification != null) {
                specification.clearPassword();
            }
        }
    }

    private record Account(String username, String displayName, String role, String passwordHash) {
    }
}
