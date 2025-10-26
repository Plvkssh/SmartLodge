package com.example.hotel.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Провайдер для создания секретных ключей JWT.
 * Обеспечивает безопасное преобразование строкового секрета в HMAC ключ.
 * Используется для подписи и верификации JWT токенов в Hotel Service.
 */
final class JwtSecretKeyProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtSecretKeyProvider.class);
    
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int REQUIRED_KEY_LENGTH = 32; // 256 бит для HS256
    private static final int MIN_SECRET_LENGTH = 16; // Минимальная рекомендуемая длина секрета

    private JwtSecretKeyProvider() {
        throw new AssertionError("Utility class - instantiation not allowed");
    }

    /**
     * Создает HMAC ключ из строкового секрета.
     * Если секрет короче требуемой длины, дополняется через хэширование.
     * Если длиннее - обрезается до REQUIRED_KEY_LENGTH.
     *
     * @param secret строковый секрет из конфигурации
     * @return SecretKey для подписи JWT токенов
     * @throws IllegalArgumentException если secret null или пустой
     * @throws SecurityException если возникают проблемы с криптографией
     */
    static SecretKey getHmacKey(String secret) {
        validateSecret(secret);
        
        byte[] keyBytes = generateKeyBytes(secret);
        byte[] normalizedKey = normalizeKeyLength(keyBytes);
        
        // Очищаем чувствительные данные из памяти
        Arrays.fill(keyBytes, (byte) 0);
        
        logger.debug("JWT key generated successfully for algorithm: {}", HMAC_ALGORITHM);
        return new SecretKeySpec(normalizedKey, HMAC_ALGORITHM);
    }

    /**
     * Генерирует байты ключа из строкового секрета.
     * Использует SHA-256 для создания детерминированного ключа из любого размера секрета.
     */
    private static byte[] generateKeyBytes(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available", e);
            throw new SecurityException("Required cryptographic algorithm not available", e);
        }
    }

    /**
     * Проверяет валидность секрета.
     */
    private static void validateSecret(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret cannot be null");
        }
        
        String trimmedSecret = secret.trim();
        if (trimmedSecret.isEmpty()) {
            throw new IllegalArgumentException("JWT secret cannot be empty or whitespace");
        }
        
        if (trimmedSecret.length() < MIN_SECRET_LENGTH) {
            logger.warn("JWT secret is too short ({} chars). Recommended minimum: {} chars", 
                       trimmedSecret.length(), MIN_SECRET_LENGTH);
        }
        
        // Проверка на слабые секреты
        if (isWeakSecret(trimmedSecret)) {
            logger.error("JWT secret is too weak. Please use a stronger secret.");
            throw new IllegalArgumentException("JWT secret is too weak");
        }
        
        logger.debug("JWT secret validation passed (length: {})", trimmedSecret.length());
    }

    /**
     * Проверяет, является ли секрет слабым.
     */
    private static boolean isWeakSecret(String secret) {
        // Проверка common weak secrets
        return secret.equals("secret") ||
               secret.equals("password") ||
               secret.equals("123456") ||
               secret.length() < 8 || // Слишком короткий
               secret.chars().allMatch(Character::isLetterOrDigit); // Нет специальных символов
    }

    /**
     * Нормализует длину ключа до REQUIRED_KEY_LENGTH.
     * Дополняет нулями если короткий, обрезает если длинный.
     */
    private static byte[] normalizeKeyLength(byte[] keyBytes) {
        if (keyBytes.length == REQUIRED_KEY_LENGTH) {
            return Arrays.copyOf(keyBytes, REQUIRED_KEY_LENGTH);
        }

        byte[] normalizedKey = new byte[REQUIRED_KEY_LENGTH];
        
        if (keyBytes.length < REQUIRED_KEY_LENGTH) {
            // Дополнение нулями
            System.arraycopy(keyBytes, 0, normalizedKey, 0, keyBytes.length);
            logger.debug("JWT key padded from {} to {} bytes", keyBytes.length, REQUIRED_KEY_LENGTH);
        } else {
            // Обрезка до нужной длины
            System.arraycopy(keyBytes, 0, normalizedKey, 0, REQUIRED_KEY_LENGTH);
            logger.debug("JWT key truncated from {} to {} bytes", keyBytes.length, REQUIRED_KEY_LENGTH);
        }
        
        return normalizedKey;
    }

    /**
     * Проверяет, соответствует ли секрет требованиям безопасности.
     * Используется при старте приложения для валидации конфигурации.
     */
    static boolean isSecretStrong(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return false;
        }
        
        String trimmedSecret = secret.trim();
        return trimmedSecret.length() >= MIN_SECRET_LENGTH &&
               !isWeakSecret(trimmedSecret);
    }

    /**
     * Возвращает рекомендуемую минимальную длину секрета.
     */
    static int getRecommendedSecretLength() {
        return MIN_SECRET_LENGTH;
    }

    /**
     * Возвращает требуемую длину ключа для алгоритма.
     */
    static int getRequiredKeyLength() {
        return REQUIRED_KEY_LENGTH;
    }

    /**
     * Генерирует безопасный случайный секрет (для тестов или начальной настройки).
     */
    static String generateSecureSecret() {
        try {
            byte[] randomBytes = new byte[32];
            java.security.SecureRandom.getInstanceStrong().nextBytes(randomBytes);
            
            // Конвертируем в base64 для читаемости
            String secret = java.util.Base64.getEncoder().encodeToString(randomBytes);
            
            // Очищаем чувствительные данные
            Arrays.fill(randomBytes, (byte) 0);
            
            logger.info("Generated secure JWT secret (length: {})", secret.length());
            return secret;
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.error("Failed to generate secure secret", e);
            throw new SecurityException("Cannot generate secure secret", e);
        }
    }
}
