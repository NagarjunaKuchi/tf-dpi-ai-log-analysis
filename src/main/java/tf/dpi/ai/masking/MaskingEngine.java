package tf.dpi.ai.masking;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex masking engine. Fails closed: oversized input, regex timeouts, unexpected errors, or any residual
 * sensitive pattern after masking raise {@link MaskingException} instead of returning partially masked text.
 * Thread-safe; the policy is immutable.
 */
public final class MaskingEngine {

    private static final String MARKER = "[MASKED:";

    private final MaskingPolicy policy;

    public MaskingEngine(MaskingPolicy policy) {
        this.policy = policy;
    }

    public MaskingPolicy policy() {
        return policy;
    }

    public String maskText(String input) {
        return mask(input).text();
    }

    public MaskingResult mask(String input) {
        if (input == null || input.isEmpty()) {
            return MaskingResult.unchanged(input == null ? "" : input);
        }
        if (input.length() > policy.maxInputChars()) {
            throw new MaskingException("Input exceeds maximum maskable size (" + policy.maxInputChars() + " chars)");
        }
        long deadline = System.nanoTime() + policy.maxDuration().toNanos();
        Map<MaskingCategory, Integer> counts = new EnumMap<>(MaskingCategory.class);
        try {
            String current = input;
            for (MaskingRule rule : policy.rules()) {
                current = apply(rule, current, deadline, counts);
            }
            verifyNoResidue(current, deadline);
            return new MaskingResult(current, counts);
        } catch (MaskingException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw new MaskingException("Masking failed unexpectedly; content withheld", e);
        }
    }

    private String apply(MaskingRule rule, String text, long deadline, Map<MaskingCategory, Integer> counts) {
        Matcher m = rule.pattern().matcher(new DeadlineCharSequence(text, deadline));
        StringBuilder out = new StringBuilder(text.length());
        int replaced = 0;
        while (m.find()) {
            String hit = m.group();
            if (hit.contains(MARKER)) {
                // Already masked (idempotent re-run): keep as is, do not count.
                m.appendReplacement(out, Matcher.quoteReplacement(hit));
            } else {
                m.appendReplacement(out, rule.replacement());
                replaced++;
            }
        }
        if (replaced == 0 && out.length() == 0) {
            return text;
        }
        m.appendTail(out);
        if (replaced > 0) {
            counts.merge(rule.category(), replaced, Integer::sum);
        }
        return out.toString();
    }

    private void verifyNoResidue(String text, long deadline) {
        for (Pattern detector : policy.residualDetectors()) {
            if (detector.matcher(new DeadlineCharSequence(text, deadline)).find()) {
                throw new MaskingException("Residual sensitive pattern detected after masking; content withheld");
            }
        }
    }

    /** CharSequence that aborts regex evaluation once the deadline passes (guards against catastrophic backtracking). */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String delegate;
        private final long deadline;
        private int calls;

        DeadlineCharSequence(String delegate, long deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0x3FFF) == 0 && System.nanoTime() - deadline > 0) {
                throw new MaskingException("Masking exceeded its time budget; content withheld");
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.substring(start, end);
        }

        @Override
        public String toString() {
            return delegate;
        }
    }
}
