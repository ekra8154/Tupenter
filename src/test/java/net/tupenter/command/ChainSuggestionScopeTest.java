package net.tupenter.command;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The ask-the-server round trip on a chained line.
 *
 * <p>Everything else about chain-aware suggestions stays in chat-bar
 * coordinates; this is the one exchange that cannot, because the server parses
 * what it is sent from the beginning and knows nothing about &&.
 */
class ChainSuggestionScopeTest {

    private static Suggestions at(int start, int end, String... texts) {
        StringRange range = StringRange.between(start, end);
        return new Suggestions(range, List.of(texts).stream()
                .map(t -> new Suggestion(range, t)).toList());
    }

    @Test
    void anUnchainedLineIsSentWholeAndComesBackUntouched() {
        ChainSuggestionScope.clear();
        String line = "//replacenear 10 ";
        assertSame(line, ChainSuggestionScope.outgoingText(line, 7));
        Suggestions answer = at(14, 17, "stone");
        assertSame(answer, ChainSuggestionScope.realign(7, answer));
    }

    @Test
    void aChainedLineIsAskedAboutItsSegmentOnly() {
        String line = "//replacenear 10 stone air && //replacenear 10 ";
        ChainSuggestionScope.reroot(30);
        assertEquals("//replacenear 10 ", ChainSuggestionScope.outgoingText(line, 7),
                "the server never sees the earlier segment, or the &&");
    }

    /**
     * And the answer comes back addressing that substring, so it has to be moved
     * before anything places a popup with it.
     */
    @Test
    void theAnswerMovesBackIntoChatBarCoordinates() {
        ChainSuggestionScope.reroot(30);
        ChainSuggestionScope.outgoingText("//replacenear 10 stone air && //replacenear 10 ", 7);

        Suggestions moved = ChainSuggestionScope.realign(7, at(17, 17, "stone", "dirt"));
        assertEquals(47, moved.getRange().getStart());
        assertEquals(47, moved.getRange().getEnd());
        for (Suggestion suggestion : moved.getList()) {
            assertEquals(47, suggestion.getRange().getStart(), suggestion.getText());
        }
    }

    /**
     * The reply is asynchronous and the cursor may have moved to another segment
     * by the time it lands. A stale answer is left alone rather than shifted by
     * an offset that now belongs to something else.
     */
    @Test
    void anAnswerToAnOlderRequestIsNotMoved() {
        ChainSuggestionScope.reroot(30);
        ChainSuggestionScope.outgoingText("//a && //b", 7);
        Suggestions stale = at(3, 3, "x");
        assertSame(stale, ChainSuggestionScope.realign(6, stale));
    }

    @Test
    void anOffsetIsSpentOnce() {
        ChainSuggestionScope.reroot(30);
        ChainSuggestionScope.outgoingText("//replacenear 10 stone air && //replacenear 10 ", 7);
        assertEquals(47, ChainSuggestionScope.realign(7, at(17, 17, "stone")).getRange().getStart());
        Suggestions again = at(17, 17, "stone");
        assertSame(again, ChainSuggestionScope.realign(7, again), "the same reply cannot be shifted twice");
    }

    @Test
    void tooltipsSurviveTheMove() {
        Message tip = () -> "a note";
        Suggestions moved = ChainSuggestionScope.shift(
                new Suggestions(StringRange.between(0, 1), List.of(
                        new Suggestion(StringRange.between(0, 1), "x", tip))), 5);
        assertEquals(5, moved.getList().get(0).getRange().getStart());
        assertSame(tip, moved.getList().get(0).getTooltip());
    }

    @Test
    void anEmptyAnswerIsLeftAlone() {
        Suggestions empty = Suggestions.empty().join();
        assertSame(empty, ChainSuggestionScope.shift(empty, 5));
    }
}
