package tf.dpi.ai.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker(200);

    @Test
    void packsSmallParagraphsTogether() {
        assertThat(chunker.chunk("one\n\ntwo\n\nthree")).containsExactly("one\n\ntwo\n\nthree");
    }

    @Test
    void splitsWhenLimitExceededAndNeverExceedsMax() {
        String p = "x".repeat(150);
        var chunks = chunker.chunk(p + "\n\n" + p + "\n\n" + p);
        assertThat(chunks).hasSize(3).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(200));
    }

    @Test
    void hardSplitsOversizedParagraphAndLongLines() {
        var chunks = chunker.chunk("y".repeat(650));
        assertThat(chunks).hasSize(4).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(200));
        assertThat(String.join("", chunks)).isEqualTo("y".repeat(650));
    }

    @Test
    void blankInputYieldsNoChunksAndIsDeterministic() {
        assertThat(chunker.chunk("  \n\n ")).isEmpty();
        assertThat(chunker.chunk("a\n\nb")).isEqualTo(chunker.chunk("a\n\nb"));
    }

    @Test
    void rejectsTinyLimit() {
        assertThatThrownBy(() -> new KnowledgeChunker(10)).isInstanceOf(IllegalArgumentException.class);
    }
}
