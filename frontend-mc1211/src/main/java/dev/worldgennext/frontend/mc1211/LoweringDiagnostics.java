// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import java.util.ArrayList;
import java.util.List;

public final class LoweringDiagnostics {
    public record Entry(String path, String code, String message, Severity severity) {
        public enum Severity { ERROR, WARNING }
        public Entry(String path, String code, String message) { this(path, code, message, Severity.ERROR); }
        public Entry { if (path == null || path.isBlank() || code == null || code.isBlank() || message == null || message.isBlank() || severity == null) throw new IllegalArgumentException("Invalid diagnostic"); }
    }
    private final List<Entry> entries = new ArrayList<>();
    public void error(String path, String code, String message) { entries.add(new Entry(path, code, message, Entry.Severity.ERROR)); }
    public void warning(String path, String code, String message) { entries.add(new Entry(path, code, message, Entry.Severity.WARNING)); }
    public void addAll(List<Entry> values) { if (values != null) entries.addAll(values); }
    public List<Entry> entries() { return List.copyOf(entries); }
    public boolean hasErrors() { return entries.stream().anyMatch(entry -> entry.severity() == Entry.Severity.ERROR); }
    public long errorCount() { return entries.stream().filter(entry -> entry.severity() == Entry.Severity.ERROR).count(); }
    public long warningCount() { return entries.stream().filter(entry -> entry.severity() == Entry.Severity.WARNING).count(); }
    public String summary() { return entries.stream().map(entry -> entry.code() + " at " + entry.path() + ": " + entry.message()).reduce((a, b) -> a + "; " + b).orElse("no diagnostics"); }
}
