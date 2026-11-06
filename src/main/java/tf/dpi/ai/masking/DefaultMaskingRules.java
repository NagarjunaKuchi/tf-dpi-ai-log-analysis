package tf.dpi.ai.masking;

import static tf.dpi.ai.masking.MaskingCategory.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Built-in masking rules. Order matters: multi-line and structured secrets first, generic identifiers last.
 * Key/value rules keep the key (group 1) so the log stays readable: {@code password=[MASKED:PASSWORD]}.
 */
final class DefaultMaskingRules {

    private static final String VALUE = "[^\\s\"',;&}\\])]+";

    private DefaultMaskingRules() {
    }

    static List<MaskingRule> rules() {
        return List.of(
                MaskingRule.of("private-key-block", PRIVATE_KEY,
                        "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY(?: BLOCK)?-----[\\s\\S]*?(?:-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY(?: BLOCK)?-----|$)",
                        MaskingRule.placeholder(PRIVATE_KEY)),
                MaskingRule.of("jwt", JWT,
                        "\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*",
                        MaskingRule.placeholder(JWT)),
                MaskingRule.of("authorization-header", AUTH_HEADER,
                        "(?i)(\\bauthorization[\"']?\\s*[:=]\\s*[\"']?)(?:(?:bearer|basic|token|negotiate)\\s+)?[^\\s,;\"']+",
                        "$1" + MaskingRule.placeholder(AUTH_HEADER)),
                MaskingRule.of("bearer-token", ACCESS_TOKEN,
                        "(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}",
                        "$1" + MaskingRule.placeholder(ACCESS_TOKEN)),
                MaskingRule.of("url-embedded-credentials", DB_CREDENTIAL,
                        "(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s:/@]+:)[^\\s@/]+(@)",
                        "$1" + MaskingRule.placeholder(DB_CREDENTIAL) + "$2"),
                MaskingRule.of("password-kv", PASSWORD,
                        "(?i)(\\b(?:db[_-]?)?(?:password|passwd|passphrase|pwd|(?:client[_-]?)?secret)[\"']?\\s*[:=]\\s*[\"']?)" + VALUE,
                        "$1" + MaskingRule.placeholder(PASSWORD)),
                MaskingRule.of("token-kv", ACCESS_TOKEN,
                        "(?i)(\\b(?:access|refresh|id|auth|session)?[_-]?token[\"']?\\s*[:=]\\s*[\"']?)" + VALUE,
                        "$1" + MaskingRule.placeholder(ACCESS_TOKEN)),
                MaskingRule.of("session-id-kv", ACCESS_TOKEN,
                        "(?i)(\\b(?:jsessionid|session[_-]?id)[\"']?\\s*[:=]\\s*[\"']?)" + VALUE,
                        "$1" + MaskingRule.placeholder(ACCESS_TOKEN)),
                MaskingRule.of("api-key-kv", API_KEY,
                        "(?i)(\\b(?:x-)?api[_-]?key[\"']?\\s*[:=]\\s*[\"']?)" + VALUE,
                        "$1" + MaskingRule.placeholder(API_KEY)),
                MaskingRule.of("api-key-vendor-patterns", API_KEY,
                        "\\b(?:(?:AKIA|ASIA)[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}|sk-[A-Za-z0-9]{20,})\\b",
                        MaskingRule.placeholder(API_KEY)),
                MaskingRule.of("db-user-kv", DB_CREDENTIAL,
                        "(?i)(\\b(?:db[_-]?user(?:name)?|user(?:name)?)[\"']?\\s*[:=]\\s*[\"']?)" + VALUE,
                        "$1" + MaskingRule.placeholder(DB_CREDENTIAL)),
                MaskingRule.of("db-user-in-auth-error", DB_CREDENTIAL,
                        "(?i)(\\bfor user\\s+[\"']?)[^\\s\"']+",
                        "$1" + MaskingRule.placeholder(DB_CREDENTIAL)),
                MaskingRule.of("email", EMAIL,
                        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b",
                        MaskingRule.placeholder(EMAIL)),
                MaskingRule.of("national-id-kv", NATIONAL_ID,
                        "(?i)(\\b(?:uin|vid|nin|national[_-]?id|aadhaar|ssn|individual[_-]?id)[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9-]+",
                        "$1" + MaskingRule.placeholder(NATIONAL_ID)),
                MaskingRule.of("personal-identifier-kv", PERSONAL_IDENTIFIER,
                        "(?i)(\\b(?:full[_-]?name|first[_-]?name|last[_-]?name|surname|date[_-]?of[_-]?birth|dob|address|postal[_-]?code)[\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\",;}]+",
                        "$1" + MaskingRule.placeholder(PERSONAL_IDENTIFIER)),
                MaskingRule.of("phone-international", PHONE,
                        "(?<![\\w.+])\\+\\d{1,3}[ -]?\\d{6,12}(?!\\d)",
                        MaskingRule.placeholder(PHONE)),
                MaskingRule.of("phone-separated", PHONE,
                        "(?<![\\w.])\\d{3}[ -]\\d{3}[ -]\\d{4}(?!\\d)",
                        MaskingRule.placeholder(PHONE)),
                MaskingRule.of("national-id-bare-uin-vid", NATIONAL_ID,
                        "(?<![\\w.:/+-])(?:\\d{16}|\\d{10})(?!\\w)",
                        MaskingRule.placeholder(NATIONAL_ID)));
    }

    /** Patterns that must not survive masking. A hit means the output is unsafe and masking fails closed. */
    static List<Pattern> residualDetectors() {
        return List.of(
                Pattern.compile("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY"),
                Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"),
                Pattern.compile("(?i)\\bbearer\\s+(?!\\[MASKED:)[A-Za-z0-9._~+/=-]{8,}"),
                Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s:/@]+:(?!\\[MASKED:)[^\\s@/]+@"),
                Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b"),
                Pattern.compile("(?i)\\b(?:password|passwd|pwd)[\"']?\\s*[:=]\\s*[\"']?(?!\\[MASKED:)[^\\s\"',;&}\\])]+"));
    }

    static Set<String> sensitiveJsonKeys() {
        return Set.of(
                "password", "passwd", "pwd", "passphrase", "secret", "clientsecret", "client_secret",
                "token", "accesstoken", "access_token", "refreshtoken", "refresh_token", "idtoken", "id_token",
                "authorization", "apikey", "api_key", "x-api-key", "privatekey", "private_key",
                "uin", "vid", "nin", "nationalid", "national_id", "aadhaar",
                "email", "phone", "phonenumber", "phone_number", "mobile",
                "fullname", "full_name", "firstname", "first_name", "lastname", "last_name",
                "dob", "dateofbirth", "date_of_birth", "address", "dbpassword", "db_password", "dbuser", "db_user");
    }
}
