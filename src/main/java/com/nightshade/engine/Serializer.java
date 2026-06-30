package com.nightshade.engine;

import com.nightshade.model.SourceFile;
import com.nightshade.model.Token;
import com.nightshade.model.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a modified token stream back into source lines.
 *
 * The Serializer reconstructs lines by walking the token list and
 * rebuilding text. For strategies that modify tokens in-place (EntropyScrambler,
 * CommentPoisoner), this reconstructs the file from the modified token values.
 *
 * For strategies that add lines (DeadCodeInjector, WhitespaceDisruptor),
 * those strategies work directly on the SourceFile's line list and bypass
 * the Serializer's token reconstruction.
 */
public class Serializer {

    /**
     * Rebuilds source lines from a token list.
     * Tokens already carry their line numbers — we reconstruct line by line.
     */
    public List<String> serialize(List<Token> tokens) {
        if (tokens.isEmpty()) return new ArrayList<>();

        // Find max line number
        int maxLine = tokens.stream()
            .mapToInt(Token::getLineNumber)
            .max()
            .orElse(1);

        // Group tokens by line
        List<StringBuilder> lineBuilders = new ArrayList<>();
        for (int i = 0; i <= maxLine; i++) {
            lineBuilders.add(new StringBuilder());
        }

        for (Token t : tokens) {
            int lineIdx = Math.min(t.getLineNumber(), maxLine);
            lineBuilders.get(lineIdx).append(t.getValue());
        }

        // Convert to list (skip index 0 since lines are 1-based)
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= maxLine; i++) {
            result.add(lineBuilders.get(i).toString());
        }
        return result;
    }

    /**
     * Applies a token-value mapping to a SourceFile's lines.
     * Used by EntropyScrambler to do direct string replacement
     * when token position tracking is sufficient.
     *
     * @param source  Original source file
     * @param mapping Map of original identifier → replacement
     * @return New line list with replacements applied
     */
    public List<String> applyMapping(SourceFile source, Map<String, String> mapping) {
        List<String> result = new ArrayList<>();
        boolean skipping = false;
        Lexer lexer = new Lexer();

        int braceDepth = 0;
        String currentClassName = "Unknown";
        String currentMethodName = null;
        boolean inMethod = false;
        int methodStartDepth = 0;

        for (String line : source.getObfuscatedLines()) {
            String trimmed = line.trim();
            
            if (trimmed.contains("@nightshade:skip")) {
                skipping = true;
                result.add(line);
                continue;
            }
            if (trimmed.contains("@nightshade:resume")) {
                skipping = false;
                result.add(line);
                continue;
            }

            // Skip renaming on package, import, or skipped lines
            if (skipping || trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                result.add(line);
                continue;
            }

            List<Token> tokens = lexer.tokenize(List.of(line));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);

                // --- SCOPE TRACKING ---
                if (token.getType() == TokenType.KEYWORD &&
                    (token.getValue().equals("class") || token.getValue().equals("interface") ||
                     token.getValue().equals("enum") || token.getValue().equals("record"))) {
                    for (int j = i + 1; j < tokens.size(); j++) {
                        if (tokens.get(j).getType() == TokenType.IDENTIFIER) {
                            currentClassName = tokens.get(j).getValue();
                            break;
                        }
                    }
                }

                if (braceDepth == 1 && token.getType() == TokenType.IDENTIFIER && i + 1 < tokens.size()) {
                    boolean looksLikeMethod = false;
                    for (int j = i + 1; j < Math.min(i + 10, tokens.size()); j++) {
                        Token peek = tokens.get(j);
                        if (peek.getType() == TokenType.WHITESPACE) continue;
                        if (peek.getType() == TokenType.SYMBOL && peek.getValue().equals("(")) {
                            looksLikeMethod = true;
                        }
                        break;
                    }
                    if (looksLikeMethod && !inMethod) {
                        currentMethodName = token.getValue();
                    }
                }

                if (token.getType() == TokenType.SYMBOL) {
                    if (token.getValue().equals("{")) {
                        braceDepth++;
                        if (currentMethodName != null && !inMethod && braceDepth == 2) {
                            inMethod = true;
                            methodStartDepth = braceDepth;
                        }
                    } else if (token.getValue().equals("}")) {
                        braceDepth = Math.max(0, braceDepth - 1);
                        if (inMethod && braceDepth < methodStartDepth) {
                            inMethod = false;
                            currentMethodName = null;
                        }
                    }
                }
                // ----------------------

                Token prevToken = previousNonWhitespace(tokens, i);
                boolean isDotCall = prevToken != null
                    && ".".equals(prevToken.getValue())
                    && token.getType() == TokenType.IDENTIFIER;

                // Allow renaming identifiers after this./super. (field access, not method call)
                boolean isThisDot = false;
                if (isDotCall) {
                    Token beforeDot = previousNonWhitespace(tokens, i - 1);
                    isThisDot = beforeDot != null && (beforeDot.getValue().equals("this") || beforeDot.getValue().equals("super"));
                }

                // Check if this identifier is in the mapping (our own method/variable)
                boolean isInMapping = mapping.containsKey(token.getValue()) || mapping.containsKey("global::" + token.getValue());

                if (token.getType() == TokenType.IDENTIFIER && (!isDotCall || isThisDot || isInMapping)) {
                    String replacement = mapping.get(token.getValue());
                    if (replacement == null) {
                        replacement = mapping.get("global::" + token.getValue());
                    }

                    if (replacement != null) {
                        sb.append(replacement);
                    } else {
                        sb.append(token.getValue());
                    }
                } else {
                    sb.append(token.getValue());
                }
            }
            result.add(sb.toString());
        }
        return result;
    }

    private Token previousNonWhitespace(List<Token> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.getType() != TokenType.WHITESPACE) {
                return token;
            }
        }
        return null;
    }

}
