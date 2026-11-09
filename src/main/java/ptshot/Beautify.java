package ptshot;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Re-indents minified JavaScript and CSS (one statement / declaration per line) so that bundles can be read, edited and cut down to
 * a single line. It is a tokenizer, not a parser: strings, template literals, regular expressions and comments are copied untouched,
 * and the code is never evaluated. Anything unexpected simply yields slightly odd spacing, never lost text.
 */
final class Beautify {
    private Beautify() {}

    static final int MAX_CHARS = 8_000_000;

    private static final Set<String> BEFORE_PAREN = new HashSet<>(Arrays.asList("if", "for", "while", "switch", "catch", "with", "return", "typeof", "in", "of", "case", "void", "delete", "throw", "else", "do", "yield", "await", "new"));
    /** Keywords after which a '/' starts a regular expression rather than a division. */
    private static final Set<String> REGEX_AFTER = new HashSet<>(Arrays.asList("return", "typeof", "case", "in", "of", "void", "delete", "throw", "else", "do", "yield", "await", "new", "instanceof"));
    private static final String[] OPS = {">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=", "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "**", "<<", ">>"};

    // previous significant token kinds
    private static final int START = 0, OPERAND = 1, OP = 2, OPEN = 3, SEP = 4, KEYWORD = 5, UNARY = 6;

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c > 127;
    }

    static String js(String src) {
        if (src.length() > MAX_CHARS) return src;
        StringBuilder o = new StringBuilder(src.length() + src.length() / 4);
        int n = src.length(), i = 0, ind = 0, ternary = 0;
        Deque<Character> stack = new ArrayDeque<>();
        Deque<Integer> tern = new ArrayDeque<>();
        int kind = START;
        boolean space = false;
        String lastWord = "";
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                i++;
                continue;
            }
            // comments
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int e = src.indexOf('\n', i);
                if (e < 0) e = n;
                gap(o, space, ind, true);
                o.append(src, i, e);
                newline(o, ind);
                i = e;
                space = false;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                gap(o, space, ind, true);
                o.append(src, i, e);
                i = e;
                space = true;
                continue;
            }
            // strings and template literals
            if (c == '"' || c == '\'' || c == '`') {
                int e = c == '`' ? template(src, i) : quoted(src, i);
                gap(o, space, ind, kind == OPERAND || kind == KEYWORD);
                o.append(src, i, e);
                i = e;
                kind = OPERAND;
                space = false;
                continue;
            }
            // words and numbers
            if (word(c)) {
                int e = i;
                while (e < n && (word(src.charAt(e)) || (src.charAt(e) == '.' && e > i && Character.isDigit(src.charAt(i))))) e++;
                String w = src.substring(i, e);
                if (o.length() > 0 && last(o) == ')') o.append(' ');
                boolean need = space && o.length() > 0 && (word(last(o)) || last(o) == '"' || last(o) == '\'' || last(o) == '`' || last(o) == ')' || last(o) == ']' || last(o) == '/' && kind == OPERAND);
                if (kind == OPERAND || kind == KEYWORD) need = space || need;
                gap(o, need, ind, need);
                o.append(w);
                lastWord = w;
                kind = REGEX_AFTER.contains(w) || BEFORE_PAREN.contains(w) ? KEYWORD : OPERAND;
                i = e;
                space = false;
                continue;
            }
            // regular expression literal
            if (c == '/' && (kind == START || kind == OP || kind == OPEN || kind == SEP || kind == UNARY || (kind == KEYWORD && REGEX_AFTER.contains(lastWord)))) {
                int e = regex(src, i);
                if (e > i) {
                    gap(o, space && kind == KEYWORD, ind, space && kind == KEYWORD);
                    o.append(src, i, e);
                    i = e;
                    kind = OPERAND;
                    space = false;
                    continue;
                }
            }
            switch (c) {
                case '{': {
                    int k = i + 1;
                    while (k < n && Character.isWhitespace(src.charAt(k))) k++;
                    trimSpace(o);
                    if (o.length() > 0 && last(o) != '\n' && last(o) != '(' && last(o) != '[') o.append(' ');
                    if (k < n && src.charAt(k) == '}') {
                        o.append("{}");
                        i = k + 1;
                        kind = OPERAND;
                        space = false;
                        afterClose(o, src, i, ind, stack);
                        continue;
                    }
                    boolean arrow = o.length() >= 2 && o.charAt(o.length() - 1) == '>' && o.charAt(o.length() - 2) == '=';
                    boolean objectLit = !arrow && (kind == OP || kind == OPEN || kind == SEP || (kind == KEYWORD && (lastWord.equals("return") || lastWord.equals("yield"))));
                    o.append('{');
                    stack.push(objectLit ? 'o' : '{');
                    tern.push(ternary);
                    ternary = 0;
                    ind++;
                    newline(o, ind);
                    kind = START;
                    break;
                }
                case '}': {
                    if (!stack.isEmpty() && (stack.peek() == '{' || stack.peek() == 'o')) {
                        stack.pop();
                        if (!tern.isEmpty()) ternary = tern.pop();
                    }
                    ind = Math.max(0, ind - 1);
                    trimSpace(o);
                    if (o.length() > 0 && last(o) != '\n') newline(o, ind);
                    o.append('}');
                    kind = OPERAND;
                    i++;
                    space = false;
                    afterClose(o, src, i, ind, stack);
                    continue;
                }
                case '(': case '[': {
                    boolean kw = kind == KEYWORD && c == '(' && BEFORE_PAREN.contains(lastWord);
                    if (kind == OPERAND) trimSpace(o);
                    if (kw) {
                        trimSpace(o);
                        o.append(' ');
                    }
                    o.append(c);
                    stack.push(c);
                    kind = OPEN;
                    break;
                }
                case ')': case ']':
                    if (!stack.isEmpty() && (stack.peek() == '(' || stack.peek() == '[')) stack.pop();
                    trimSpace(o);
                    o.append(c);
                    kind = OPERAND;
                    break;
                case ';':
                    trimSpace(o);
                    o.append(';');
                    if (!stack.isEmpty() && stack.peek() == '(') o.append(' ');
                    else if (!(i + 1 < n && src.charAt(i + 1) == '}')) newline(o, ind);
                    kind = START;
                    break;
                case ',':
                    trimSpace(o);
                    o.append(',');
                    if (!stack.isEmpty() && stack.peek() == 'o') newline(o, ind);
                    else o.append(' ');
                    kind = SEP;
                    break;
                case '.':
                    if (src.startsWith("...", i)) {
                        gap(o, space, ind, false);
                        o.append("...");
                        i += 3;
                        kind = UNARY;
                        space = false;
                        continue;
                    }
                    trimSpace(o);
                    o.append('.');
                    kind = SEP;
                    break;
                case ':': {
                    trimSpace(o);
                    if (ternary > 0) {
                        ternary--;
                        o.append(" : ");
                    } else {
                        o.append(": ");
                    }
                    kind = OP;
                    break;
                }
                case '?': {
                    if (src.startsWith("?.", i) && !(i + 2 < n && Character.isDigit(src.charAt(i + 2)))) {
                        trimSpace(o);
                        o.append("?.");
                        i += 2;
                        kind = SEP;
                        space = false;
                        continue;
                    }
                    if (src.startsWith("??", i)) {
                        String op = src.startsWith("??=", i) ? "??=" : "??";
                        trimSpace(o);
                        o.append(' ').append(op).append(' ');
                        i += op.length();
                        kind = OP;
                        space = false;
                        continue;
                    }
                    ternary++;
                    trimSpace(o);
                    o.append(" ? ");
                    kind = OP;
                    break;
                }
                default: {
                    String op = String.valueOf(c);
                    for (String t : OPS) if (src.startsWith(t, i)) { op = t; break; }
                    boolean operand = kind == OPERAND;
                    boolean incdec = op.equals("++") || op.equals("--");
                    boolean unaryOnly = op.equals("!") || op.equals("~");
                    boolean unary = unaryOnly || (!operand && (op.equals("+") || op.equals("-") || incdec));
                    if (incdec || unary) {
                        // "a - -b" must not become "a--b"; "x++ + y" keeps its space
                        boolean sp = !operand && (kind == OP || kind == KEYWORD) && o.length() > 0 && last(o) != ' ' && last(o) != '(' && last(o) != '[';
                        if (operand && incdec) trimSpace(o);
                        else gap(o, false, ind, sp || (space && o.length() > 0 && last(o) == op.charAt(0)));
                        o.append(op);
                        kind = operand && incdec ? OPERAND : UNARY;
                    } else {
                        trimSpace(o);
                        o.append(' ').append(op).append(' ');
                        kind = OP;
                    }
                    i += op.length();
                    space = false;
                    continue;
                }
            }
            i++;
            space = false;
        }
        String r = o.toString().replaceAll("[ \\t]+\\n", "\n");
        return r.trim();
    }

    /** After a closing brace: stay on the line for "} else", "}," "});" ... otherwise start a new line. */
    private static void afterClose(StringBuilder o, String src, int i, int ind, Deque<Character> stack) {
        int n = src.length(), k = i;
        while (k < n && Character.isWhitespace(src.charAt(k))) k++;
        if (k >= n) return;
        char c = src.charAt(k);
        if (c == ',' || c == ';' || c == ')' || c == ']' || c == '.' || c == ':' || c == '}') {
            if (c == '}') newline(o, ind);
            return;
        }
        if (startsWord(src, k, "else") || startsWord(src, k, "catch") || startsWord(src, k, "finally") || (startsWord(src, k, "while") && false)) {
            o.append(' ');
            return;
        }
        if (!stack.isEmpty() && stack.peek() == '(') return; // object literal inside call arguments: ")" / "," follow
        newline(o, ind);
    }

    private static boolean startsWord(String s, int i, String w) {
        return s.startsWith(w, i) && (i + w.length() >= s.length() || !word(s.charAt(i + w.length())));
    }

    private static char last(StringBuilder o) {
        return o.length() == 0 ? '\n' : o.charAt(o.length() - 1);
    }

    private static void trimSpace(StringBuilder o) {
        int l = o.length();
        while (l > 0 && (o.charAt(l - 1) == ' ')) l--;
        o.setLength(l);
    }

    private static void newline(StringBuilder o, int ind) {
        trimSpace(o);
        if (o.length() > 0 && last(o) != '\n') o.append('\n');
        for (int i = 0; i < ind; i++) o.append("  ");
    }

    private static void gap(StringBuilder o, boolean space, int ind, boolean force) {
        if (force && o.length() > 0 && last(o) != ' ' && last(o) != '\n' && last(o) != '(' && last(o) != '[') o.append(' ');
    }

    private static int quoted(String s, int i) {
        char q = s.charAt(i);
        int n = s.length(), k = i + 1;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\\') k += 2;
            else if (c == q) return k + 1;
            else if (c == '\n') return k; // unterminated: stop at the line end
            else k++;
        }
        return n;
    }

    /** End of a template literal, including nested ${ ... } expressions that may contain their own strings and templates. */
    private static int template(String s, int i) {
        int n = s.length(), k = i + 1;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\\') k += 2;
            else if (c == '`') return k + 1;
            else if (c == '$' && k + 1 < n && s.charAt(k + 1) == '{') {
                int depth = 1;
                k += 2;
                while (k < n && depth > 0) {
                    char d = s.charAt(k);
                    if (d == '"' || d == '\'') k = quoted(s, k);
                    else if (d == '`') k = template(s, k);
                    else {
                        if (d == '{') depth++;
                        else if (d == '}') depth--;
                        k++;
                    }
                }
            } else k++;
        }
        return n;
    }

    /** End of a regular expression literal starting at '/', or {@code i} if this does not look like one. */
    private static int regex(String s, int i) {
        int n = s.length(), k = i + 1;
        boolean cls = false;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\n') return i;
            if (c == '\\') k += 2;
            else if (c == '[') { cls = true; k++; }
            else if (c == ']') { cls = false; k++; }
            else if (c == '/' && !cls) {
                k++;
                while (k < n && Character.isLetter(s.charAt(k))) k++;
                return k;
            } else k++;
        }
        return i;
    }

    // ------------------------------------------------------------------ CSS

    static String css(String src) {
        if (src.length() > MAX_CHARS) return src;
        StringBuilder o = new StringBuilder(src.length() + src.length() / 4);
        int n = src.length(), i = 0, ind = 0, paren = 0;
        boolean space = false;
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                if (o.length() > 0 && last(o) != '\n') newline(o, ind);
                o.append(src, i, e);
                newline(o, ind);
                i = e;
                space = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                int e = quoted(src, i);
                if (space && o.length() > 0 && last(o) != '\n' && last(o) != '(') o.append(' ');
                o.append(src, i, e);
                i = e;
                space = false;
                continue;
            }
            switch (c) {
                case '{':
                    trimSpace(o);
                    if (o.length() > 0 && last(o) != '\n') o.append(' ');
                    o.append('{');
                    ind++;
                    newline(o, ind);
                    break;
                case '}':
                    ind = Math.max(0, ind - 1);
                    trimSpace(o);
                    newline(o, ind);
                    o.append('}');
                    newline(o, ind);
                    break;
                case ';':
                    o.append(';');
                    if (paren == 0) newline(o, ind);
                    break;
                case '(': {
                    if (atRuleName(o)) o.append(' ');
                    if (o.length() >= 3 && o.substring(o.length() - 3).equalsIgnoreCase("url")) {
                        int e = i + 1;
                        while (e < n && src.charAt(e) != ')') e = (src.charAt(e) == '"' || src.charAt(e) == '\'') ? quoted(src, e) : e + 1;
                        e = Math.min(n, e + 1);
                        o.append(src, i, e);
                        i = e - 1;
                        break;
                    }
                    paren++;
                    o.append(c);
                    break;
                }
                case ')':
                    paren = Math.max(0, paren - 1);
                    o.append(c);
                    break;
                case ',':
                    o.append(", ");
                    while (i + 1 < n && Character.isWhitespace(src.charAt(i + 1))) i++;
                    break;
                case ':':
                    o.append(':');
                    if (paren == 0 && !(i + 1 < n && src.charAt(i + 1) == ':') && !(i > 0 && src.charAt(i - 1) == ':') && declaration(src, i)) o.append(' ');
                    while (paren == 0 && declaration(src, i) && i + 1 < n && Character.isWhitespace(src.charAt(i + 1))) i++;
                    break;
                default:
                    if (space && o.length() > 0 && last(o) != '\n' && last(o) != '(' && last(o) != ' ') o.append(' ');
                    o.append(c);
            }
            i++;
            space = false;
        }
        return o.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** True while the current output line is an at-rule name such as "@media" (so "(" gets a space before it). */
    private static boolean atRuleName(StringBuilder o) {
        int ls = o.lastIndexOf("\n") + 1;
        while (ls < o.length() && o.charAt(ls) == ' ') ls++;
        return ls < o.length() && o.charAt(ls) == '@' && Character.isLetter(last(o));
    }

    /** True if the ':' at {@code i} separates a property from its value (a ';' or '}' comes before any '{'), false for selectors like a:hover. */
    private static boolean declaration(String s, int i) {
        for (int k = i + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') return false;
            if (c == ';' || c == '}') return true;
        }
        return true;
    }
}
