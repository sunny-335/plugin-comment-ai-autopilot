package top.nxxy335.commentaiautopilot.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Utility for generating Gravatar/Cravatar avatar URLs from email addresses.
 */
@Slf4j
public class GravatarUtil {

    private static final String CRAVATAR_BASE_URL = "https://cn.cravatar.com/avatar/";

    private GravatarUtil() {}

    /**
     * Generate Cravatar URL from email address using SHA-256 hash.
     *
     * @param email the email address
     * @return the avatar URL, or empty string if generation fails
     */
    public static String generateUrl(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            var hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return CRAVATAR_BASE_URL + hexString;
        } catch (Exception e) {
            log.error("Failed to generate Gravatar URL: {}", e.getMessage());
            return "";
        }
    }
}
