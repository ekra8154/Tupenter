package net.tupenter.command;

import java.util.ArrayList;
import java.util.List;

public final class CommandParsingProcessor {
    private CommandParsingProcessor() {
    }

    public static Result process(String command, boolean commandChainingEnabled, boolean numberMathEnabled) {
        List<String> commands = commandChainingEnabled
                ? splitCommands(command)
                : List.of(command);

        boolean changed = commands.size() > 1;
        List<String> processed = new ArrayList<>(commands.size());

        for (String candidate : commands) {
            String normalized = normalizeSegment(candidate);
            if (normalized == null) {
                return Result.unchanged(command);
            }

            String rewritten = numberMathEnabled ? CommandMathParser.applyNumberMath(normalized) : normalized;
            if (!rewritten.equals(normalized)) {
                changed = true;
            }
            processed.add(rewritten);
        }

        if (!changed) {
            return Result.unchanged(command);
        }

        return new Result(processed, true);
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

    private static String normalizeSegment(String segment) {
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
            if (trimmed.isEmpty()) {
                return null;
            }
        }

        return trimmed;
    }

    public record Result(List<String> commands, boolean changed) {
        public static Result unchanged(String originalCommand) {
            return new Result(List.of(originalCommand), false);
        }
    }
}
