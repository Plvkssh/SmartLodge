package com.example.booking.security;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Провайдер для создания секретных ключей JWT.
 * Обеспечивает безопасное преобразование строкового секрета в HMAC ключ.
 */
final class JwtSecretKeyProvider {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int REQUIRED_KEY_LENGTH = 32; // 256 бит для HS256

    private JwtSecretKeyProvider() {
        throw new AssertionError("Utility class - instantiation not allowed");
    }

    /**
     * Создает HMAC ключ из строкового секрета.
     * Если секрет короче требуемой длины, дополняется нулями.
     * Если длиннее - обрезается до REQUIRED_KEY_LENGTH.
     *
     * @param secret строковый секрет из конфигурации
     * @return SecretKey для подписи JWT токенов
     * @throws IllegalArgumentException если secret null или пустой
     */
    static SecretKey getHmacKey(String secret) {
        validateSecret(secret);
        
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] normalizedKey = normalizeKeyLength(keyBytes);
        
        return new SecretKeySpec(normalizedKey, HMAC_ALGORITHM);
    }

    /**
     * Проверяет валидность секрета.
     */
    private static void validateSecret(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT secret cannot be null or empty");
        }
        
        if (secret.length() < REQUIRED_KEY_LENGTH) {
            // Логирование предупреждения в реальном приложении
            System.err.printf("Warning: JWT secret is too short (%d chars). Recommended minimum: %d chars%n",
                    secret.length(), REQUIRED_KEY_LENGTH);
        }
    }

    /**
     * Нормализует длину ключа до REQUIRED_KEY_LENGTH.
     * Дополняет нулями если короткий, обрезает если длинный.
     */
    private static byte[] normalizeKeyLength(byte[] keyBytes) {
        if (keyBytes.length == REQUIRED_KEY_LENGTH) {
            return keyBytes;
        }

        byte[] normalizedKey = new byte[REQUIRED_KEY_LENGTH];
        
        if (keyBytes.length < REQUIRED_KEY_LENGTH) {
            // Дополнение нулями
            System.arraycopy(keyBytes, 0, normalizedKey, 0, keyBytes.length);
            // Оставшаяся часть уже заполнена нулями
        } else {
            // Обрезка до нужной длины
            System.arraycopy(keyBytes, 0, normalizedKey, 0, REQUIRED_KEY_LENGTH);
            // В продакшн здесь можно добавить логирование об обрезке
        }
        
        return normalizedKey;
    }

    /**
     * Проверяет, соответствует ли секрет требованиям безопасности.
     * Используется при старте приложения для валидации конфигурации.
     */
    static boolean isSecretStrong(String secret) {
        return secret != null && 
               secret.length() >= REQUIRED_KEY_LENGTH && 
               !secret.trim().isEmpty();
    }

    /**
     * Возвращает рекомендуемую минимальную длину секрета.
     */
    static int getRecommendedSecretLength() {
        return REQUIRED_KEY_LENGTH;
    }
}
