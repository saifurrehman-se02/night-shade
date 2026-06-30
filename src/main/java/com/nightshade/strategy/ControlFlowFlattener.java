package com.nightshade.strategy;

import com.nightshade.model.*;
import java.util.*;
import java.util.regex.*;

public class ControlFlowFlattener implements PoisonStrategy {

    private boolean enabled = false; // disabled by default — aggressive
    
    @Override public String getName()          { return "Control Flow Flattening"; }
    @Override public String getDescription()   { return "Rewrites method bodies into switch-dispatch loops — changes code structure, not just names"; }
    @Override public String getResearchBasis() { return "Structure-level obfuscation — survives variable normalization and reformatting"; }
    @Override public boolean isEnabled()       { return enabled; }
    @Override public void setEnabled(boolean e){ this.enabled = e; }

    // Detects private/package-private method declarations with optional annotations,
    // generics, and varied modifiers
    private static final Pattern PRIVATE_METHOD = Pattern.compile(
        "^(\\s*)((?:private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?(?:<[^>]++>\\s+)?\\w+(?:<[^>]++)?\\s+(\\w+)\\s*\\([^)]*\\))\\s*\\{\\s*$");

    @Override
    public ObfuscationResult apply(SourceFile source, ASTNode ast, SymbolTable symbols) {
        List<String> lines = new ArrayList<>(source.getObfuscatedLines());
        int flattenedCount = 0;
        int totalMethods = 0;

        boolean skipping = false;
        // Find private methods and flatten them
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.contains("@nightshade:skip")) skipping = true;
            if (trimmed.contains("@nightshade:resume")) skipping = false;

            if (skipping) continue;

            Matcher m = PRIVATE_METHOD.matcher(line);
            if (!m.matches()) continue;
            totalMethods++;

            String indent = m.group(1);
            String ext = source.getExtension();
            // Find the closing brace of this method
            int braceDepth = 1;
            int bodyStart = i + 1;
            int bodyEnd = -1;
            for (int j = bodyStart; j < lines.size(); j++) {
                String cleanLine = stripCommentsAndStrings(lines.get(j), ext);
                for (char c : cleanLine.toCharArray()) {
                    if (c == '{') braceDepth++;
                    if (c == '}') braceDepth--;
                }
                if (braceDepth == 0) { bodyEnd = j; break; }
            }
            if (bodyEnd == -1 || bodyEnd - bodyStart < 3) continue;

            // Extract body statements and validate safety
            List<String> bodyStatements = new ArrayList<>();
            String returnStatement = null;
            boolean hasMultipleReturns = false;
            boolean hasComplexStructures = false;
            int innerBraceDepth = 0;

            for (int j = bodyStart; j < bodyEnd; j++) {
                String bodyLine = lines.get(j);
                String bodyTrimmed = bodyLine.trim();
                if (bodyTrimmed.isEmpty()) continue;

                // Track nested braces to avoid splitting control blocks
                for (char c : bodyLine.toCharArray()) {
                    if (c == '{') innerBraceDepth++;
                    if (c == '}') innerBraceDepth--;
                }

                if (bodyTrimmed.contains("if ") || bodyTrimmed.contains("if(") ||
                    bodyTrimmed.contains("for ") || bodyTrimmed.contains("for(") ||
                    bodyTrimmed.contains("while ") || bodyTrimmed.contains("while(") ||
                    bodyTrimmed.contains("switch ") || bodyTrimmed.contains("switch(") ||
                    bodyTrimmed.contains("try ") || bodyTrimmed.contains("try{") ||
                    bodyTrimmed.contains("catch ") || bodyTrimmed.contains("catch(") ||
                    bodyTrimmed.contains("finally")) {
                    hasComplexStructures = true;
                }

                if (bodyTrimmed.startsWith("return ")) {
                    if (returnStatement != null) {
                        hasMultipleReturns = true;
                    }
                    returnStatement = bodyTrimmed;
                } else {
                    bodyStatements.add(bodyTrimmed);
                }
            }
            
            if (innerBraceDepth != 0 || hasMultipleReturns || hasComplexStructures || bodyStatements.size() < 2) {
                continue; // Skip: unsafe to flatten
            }

            String stateVar = "_ns_state";
            
            // Build the flattened version with proper scoping
            // FIX: Wrap switch in scope block so local variables are visible across cases
            List<String> flattened = new ArrayList<>();
            flattened.add(indent + "    int " + stateVar + " = 0;");
            flattened.add(indent + "    { // scope block for local variable visibility");
            flattened.add(indent + "        while (" + stateVar + " != -1) {");
            flattened.add(indent + "            switch (" + stateVar + ") {");
            for (int s = 0; s < bodyStatements.size(); s++) {
                flattened.add(indent + "            case " + s + ": " 
                    + bodyStatements.get(s) + " " + stateVar + " = " + (s+1) + "; break;");
            }
            flattened.add(indent + "            case " + bodyStatements.size() 
                + ": " + stateVar + " = -1; break;");
            flattened.add(indent + "        }");
            flattened.add(indent + "    }");
            if (returnStatement != null) {
                flattened.add(indent + "    " + returnStatement);
            }

            // Replace original body with flattened version
            List<String> before = new ArrayList<>(lines.subList(0, bodyStart));
            List<String> after = new ArrayList<>(lines.subList(bodyEnd, lines.size()));
            List<String> newLines = new ArrayList<>(before);
            newLines.addAll(flattened);
            newLines.addAll(after);
            // Adjust loop index: skip past the flattened block we just inserted
            i = bodyStart + flattened.size() - 1;
            lines = newLines;
            flattenedCount++;
        }

        SourceFile modified = new SourceFile(source.getAbsolutePath(), source.getRawLines());
        modified.setObfuscatedLines(lines);

        ObfuscationResult result = new ObfuscationResult(source, modified, 0.0);
        result.setTotalMethods(Math.max(1, totalMethods));
        return result;
    }

    private String stripCommentsAndStrings(String line, String ext) {
        String clean = line;
        if (ext.equals(".py")) {
            clean = clean.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "");
            clean = clean.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "");
            int hashIdx = clean.indexOf('#');
            if (hashIdx >= 0) clean = clean.substring(0, hashIdx);
        } else {
            clean = clean.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "");
            int commentIdx = clean.indexOf("//");
            if (commentIdx >= 0) clean = clean.substring(0, commentIdx);
        }
        return clean;
    }
}