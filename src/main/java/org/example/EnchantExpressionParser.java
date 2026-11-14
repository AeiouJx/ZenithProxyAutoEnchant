package org.example;

/**
 * Parser for the wiki preset expression language.
 * Grammar: expression := term ("+" term)*; term := token | (expression) | [expression] | {expression},
 * with an optional @integer suffix on a merge. A token's trailing _integer is its enchantment level.
 * The catalog interprets the targetPlaceholder token as the equipment and other leaves as books.
 */
public final class EnchantExpressionParser {
    public sealed interface Node permits Leaf, Merge { }
    public record Leaf(String name, int level) implements Node { }
    public record Merge(Node left, Node right, int expectedCost) implements Node { }

    private String source;
    private int cursor;

    public Node parse(String expression) {
        source = expression;
        cursor = 0;
        Node node = parseSequence('\0');
        skipWhitespace();
        if (cursor != source.length()) throw error("Unexpected trailing expression");
        return node;
    }

    private Node parseSequence(char end) {
        Node node = parseTerm();
        while (true) {
            skipWhitespace();
            if (cursor >= source.length() || (end != '\0' && source.charAt(cursor) == end)) break;
            if (source.charAt(cursor) != '+') throw error("Expected '+' or closing bracket");
            cursor++;
            Node right = parseTerm();
            node = new Merge(node, right, -1);
        }
        if (end != '\0') {
            skipWhitespace();
            if (cursor >= source.length() || source.charAt(cursor) != end) throw error("Missing closing bracket " + end);
            cursor++;
        }
        return node;
    }

    private Node parseTerm() {
        skipWhitespace();
        if (cursor >= source.length()) throw error("Expected enchantItem or enchantment token");
        char first = source.charAt(cursor);
        Node node;
        if (first == '(' || first == '[' || first == '{') {
            cursor++;
            char end = first == '(' ? ')' : first == '[' ? ']' : '}';
            node = parseSequence(end);
        } else {
            int start = cursor;
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (Character.isWhitespace(c) || c == '+' || c == ')' || c == ']' || c == '}' || c == '@') break;
                cursor++;
            }
            if (start == cursor) throw error("Expected token");
            String token = source.substring(start, cursor);
            int underscore = token.lastIndexOf('_');
            int level = 1;
            String name = token;
            if (underscore > 0 && underscore < token.length() - 1) {
                String suffix = token.substring(underscore + 1);
                if (suffix.chars().allMatch(Character::isDigit)) {
                    name = token.substring(0, underscore);
                    level = Integer.parseInt(suffix);
                }
            }
            node = new Leaf(name, level);
        }
        skipWhitespace();
        if (cursor < source.length() && source.charAt(cursor) == '@') {
            cursor++;
            skipWhitespace();
            int start = cursor;
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
            if (start == cursor) throw error("Expected cost after '@'");
            int cost = Integer.parseInt(source.substring(start, cursor));
            if (!(node instanceof Merge merge)) throw error("Cost annotation must follow a combination");
            node = new Merge(merge.left(), merge.right(), cost);
        }
        return node;
    }

    private void skipWhitespace() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + cursor + " in: " + source);
    }
}
