package tf.dpi.ai.masking;

/** Categories of sensitive data; the name appears in the placeholder, e.g. {@code [MASKED:PASSWORD]}. */
public enum MaskingCategory {
    PASSWORD,
    ACCESS_TOKEN,
    JWT,
    API_KEY,
    AUTH_HEADER,
    PERSONAL_IDENTIFIER,
    NATIONAL_ID,
    PHONE,
    EMAIL,
    DB_CREDENTIAL,
    PRIVATE_KEY,
    CUSTOM
}
