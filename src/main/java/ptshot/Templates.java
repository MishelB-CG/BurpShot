package ptshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starting points for table layers that imitate common Burp Suite tables. Everything stays editable afterwards. */
final class Templates {
    private Templates() {}

    private static List<String> r(String... c) {
        return new ArrayList<>(Arrays.asList(c));
    }

    @SafeVarargs
    private static List<List<String>> t(List<String>... rows) {
        return new ArrayList<>(Arrays.asList(rows));
    }

    /** Name -> rows (row 0 = header). A fresh copy is returned every time. */
    static Map<String, List<List<String>>> all() {
        Map<String, List<List<String>>> m = new LinkedHashMap<>();
        m.put("Intruder - attack results", t(
                r("Request", "Payload", "Status code", "Error", "Timeout", "Length", "Comment"),
                r("1", "null", "201", "", "", "412", ""),
                r("2", "null", "201", "", "", "412", ""),
                r("…", "…", "…", "…", "…", "…", "…"),
                r("100", "null", "201", "", "", "412", "")));
        m.put("Proxy - HTTP history", t(
                r("#", "Host", "Method", "URL", "Params", "Edited", "Status code", "Length", "MIME type", "Extension", "Title"),
                r("1", "https://example.com", "GET", "/", "", "", "200", "5120", "HTML", "", "Example"),
                r("2", "https://example.com", "POST", "/api/login", "✓", "", "302", "0", "", "", "")));
        m.put("Logger", t(
                r("#", "Tool", "Method", "Host", "Path", "Status", "Length", "Time"),
                r("1", "Proxy", "GET", "example.com", "/", "200", "5120", "10:42:01"),
                r("2", "Repeater", "POST", "example.com", "/api/login", "302", "0", "10:42:09")));
        m.put("Collaborator - interactions", t(
                r("#", "Time", "Type", "Payload", "Source IP address", "Comment"),
                r("1", "2026-01-01 10:42:01 UTC", "DNS", "abcdefghijklmnop", "203.0.113.7", ""),
                r("2", "2026-01-01 10:42:02 UTC", "HTTP", "abcdefghijklmnop", "203.0.113.7", "")));
        m.put("Target - site map", t(
                r("Host", "Method", "URL", "Params", "Status code", "Length", "MIME type", "Title"),
                r("https://example.com", "GET", "/", "", "200", "5120", "HTML", "Example"),
                r("https://example.com", "GET", "/admin", "", "403", "310", "HTML", "Forbidden")));
        m.put("Scanner - issues", t(
                r("#", "Issue type", "Host", "Path", "Severity", "Confidence"),
                r("1", "SQL injection", "https://example.com", "/search", "High", "Certain"),
                r("2", "Cross-site scripting (reflected)", "https://example.com", "/q", "High", "Certain")));
        m.put("Blank table (3 x 3)", t(
                r("Column 1", "Column 2", "Column 3"),
                r("", "", ""),
                r("", "", "")));
        return m;
    }
}
