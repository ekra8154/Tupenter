package net.tupenter.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.tupenter.config.TupenterConfig;

public final class CommandParsingProcessor {
    private static final int MAX_ALIAS_EXPANSIONS = 50;

    private CommandParsingProcessor() {
    }

    public static Result process(String command, boolean commandChainingEnabled, TupenterConfig.NumberMathMode numberMathMode, Map<String, String> aliases) {
        List<OutgoingSegment> expandedCommands = new ArrayList<>();
        ExpansionState expansionState = new ExpansionState();
        boolean allowChatSegments = containsAlias(command, aliases);
        boolean changed = expandCommand("/" + command, commandChainingEnabled, aliases, expansionState, expandedCommands, allowChatSegments);

        if (expansionState.limitExceeded()) {
            return Result.aliasExpansionLimitExceeded(MAX_ALIAS_EXPANSIONS);
        }

        if (expandedCommands.isEmpty()) {
            return Result.unchanged(command);
        }

        List<OutgoingSegment> processed = new ArrayList<>(expandedCommands.size());
        for (OutgoingSegment candidate : expandedCommands) {
            String rewritten = candidate.isCommand() && numberMathMode != TupenterConfig.NumberMathMode.DISABLED
                    ? CommandMathParser.applyNumberMath(candidate.content(), numberMathMode)
                    : candidate.content();
            if (!rewritten.equals(candidate.content())) {
                changed = true;
            }
            processed.add(new OutgoingSegment(rewritten, candidate.isCommand()));
        }

        if (!changed) {
            return Result.unchanged(command);
        }

        return new Result(processed, true, false, 0);
    }

    private static boolean expandCommand(String command, boolean commandChainingEnabled, Map<String, String> aliases, ExpansionState expansionState, List<OutgoingSegment> output, boolean allowChatSegments) {
        OutgoingSegment normalized = normalizeSegment(command);
        if (normalized == null) {
            return false;
        }

        AliasExpansion expansion = expandAlias(normalized.content(), aliases, expansionState);
        if (expansionState.limitExceeded()) {
            return false;
        }
        boolean changed = !expansion.command().equals(normalized.content());
        List<String> segments = commandChainingEnabled ? splitCommands(expansion.command()) : List.of(expansion.command());
        if (segments.size() > 1) {
            changed = true;
        }

        for (String segment : segments) {
            OutgoingSegment normalizedSegment = normalizeSegment(segment);
            if (normalizedSegment == null) {
                return false;
            }

            if (!allowChatSegments && !normalizedSegment.isCommand()) {
                normalizedSegment = new OutgoingSegment(normalizedSegment.content(), true);
            }

            if (containsAlias(normalizedSegment.content(), aliases)) {
                boolean nestedChanged = expandCommand(
                        normalizedSegment.isCommand() ? "/" + normalizedSegment.content() : normalizedSegment.content(),
                        commandChainingEnabled,
                        aliases,
                        expansionState,
                        output,
                        true
                );
                if (expansionState.limitExceeded()) {
                    return false;
                }
                changed = changed || nestedChanged;
            } else {
                output.add(normalizedSegment);
            }
        }

        return changed;
    }

    private static AliasExpansion expandAlias(String command, Map<String, String> aliases, ExpansionState expansionState) {
        String normalizedCommand = stripLeadingSlash(command.trim());
        int separator = findFirstWhitespace(normalizedCommand);
        String aliasName = separator >= 0 ? normalizedCommand.substring(0, separator) : normalizedCommand;
        String remainder = separator >= 0 ? normalizedCommand.substring(separator).trim() : "";

        String aliasBody = aliases.get(aliasName.toLowerCase());
        if (aliasBody == null) {
            return new AliasExpansion(command);
        }

        expansionState.increment();
        if (expansionState.limitExceeded()) {
            return new AliasExpansion(command);
        }

        String normalizedBody = aliasBody.trim();
        if (normalizedBody.isEmpty()) {
            return new AliasExpansion(command);
        }

        if (!remainder.isEmpty()) {
            normalizedBody = normalizedBody + " " + remainder;
        }

        return new AliasExpansion(normalizedBody);
    }

    private static boolean containsAlias(String command, Map<String, String> aliases) {
        String normalizedCommand = stripLeadingSlash(command.trim());
        int separator = findFirstWhitespace(normalizedCommand);
        String aliasName = separator >= 0 ? normalizedCommand.substring(0, separator) : normalizedCommand;
        return aliases.containsKey(aliasName.toLowerCase());
    }

    private static List<String> splitCommands(String command) {
        List<String> commands = new ArrayList<>();
        int segmentStart = 0;
        boolean insideMathMarker = false;

        for (int i = 0; i < command.length() - 1; i++) {
            char current = command.charAt(i);
            if (current == '$') {
                insideMathMarker = !insideMathMarker;
                continue;
            }

            if (!insideMathMarker && current == '&' && command.charAt(i + 1) == '&') {
                commands.add(command.substring(segmentStart, i));
                segmentStart = i + 2;
                i++;
            }
        }

        commands.add(command.substring(segmentStart));
        return commands;
    }

    private static OutgoingSegment normalizeSegment(String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        boolean command = false;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            command = true;
        }

        return new OutgoingSegment(trimmed, command);
    }

    private static int findFirstWhitespace(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (Character.isWhitespace(command.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private record AliasExpansion(String command) {
    }

    public record OutgoingSegment(String content, boolean isCommand) {
    }

    private static final class ExpansionState {
        private int aliasExpansions;
        private boolean limitExceeded;

        private void increment() {
            aliasExpansions++;
            if (aliasExpansions > MAX_ALIAS_EXPANSIONS) {
                limitExceeded = true;
            }
        }

        private boolean limitExceeded() {
            return limitExceeded;
        }
    }

    public record Result(List<OutgoingSegment> commands, boolean changed, boolean aliasExpansionLimitExceeded, int aliasExpansionLimit) {
        public static Result unchanged(String originalCommand) {
            return new Result(List.of(new OutgoingSegment(stripLeadingSlash(originalCommand.trim()), true)), false, false, 0);
        }

        public static Result aliasExpansionLimitExceeded(int limit) {
            return new Result(List.of(), false, true, limit);
        }
    }

    private static String stripLeadingSlash(String command) {
        if (command.startsWith("/")) {
            return command.substring(1).trim();
        }
        return command;
    }
}
