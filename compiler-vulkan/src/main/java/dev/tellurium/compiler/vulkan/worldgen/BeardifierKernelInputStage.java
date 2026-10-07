// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Moves captured raw kernel carriers to a request-owned metadata suffix. */
public final class BeardifierKernelInputStage {
    public static final int KERNEL_WORDS = 13824;
    private static final Pattern TABLE = Pattern.compile(
            "\\bconst\\s+uint\\s+wg_beard_kernel\\s*\\[\\s*13824\\s*]\\s*=\\s*"
                    + "uint\\s*\\[\\s*13824\\s*]\\s*\\(([^)]*)\\)\\s*;", Pattern.DOTALL);
    private static final Pattern LITERAL = Pattern.compile("(?:0x[0-9a-fA-F]+|[0-9]+)u");
    private static final Pattern IDENTIFIER = Pattern.compile("\\bwg_beard_kernel\\b");
    private static final Pattern LOOKUP_NAME = Pattern.compile("\\bwg_beard_kernel_lookup\\b");
    private static final Pattern READ = Pattern.compile(
            "\\bwg_beard_kernel\\s*\\[\\s*(kernelZ\\s*\\*\\s*576\\s*\\+\\s*"
                    + "kernelX\\s*\\*\\s*24\\s*\\+\\s*kernelY)\\s*]");

    private BeardifierKernelInputStage() { }

    public static Stage bind(String source, int rowWords) {
        validateStride(rowWords);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Beardifier source required");
        if (LOOKUP_NAME.matcher(source).find()) {
            throw new IllegalArgumentException("Beardifier lookup helper already present");
        }
        Matcher table = TABLE.matcher(source);
        if (!table.find()) throw new IllegalArgumentException("Canonical Beardifier kernel table required");
        int start = table.start();
        int end = table.end();
        String[] literals = table.group(1).split(",", -1);
        if (table.find()) throw new IllegalArgumentException("Duplicate Beardifier kernel table");
        if (literals.length != KERNEL_WORDS) {
            throw new IllegalArgumentException("Beardifier kernel must contain exactly " + KERNEL_WORDS + " words");
        }
        int[] words = new int[KERNEL_WORDS];
        for (int i = 0; i < words.length; i++) {
            String literal = literals[i].strip();
            if (!LITERAL.matcher(literal).matches()) {
                throw new IllegalArgumentException("Malformed Beardifier uint literal at " + i);
            }
            boolean hex = literal.startsWith("0x");
            try {
                long value = Long.parseLong(literal.substring(hex ? 2 : 0, literal.length() - 1), hex ? 16 : 10);
                if (value > 0xffff_ffffL) throw new NumberFormatException("uint overflow");
                words[i] = (int) value;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Beardifier uint overflow at " + i, invalid);
            }
        }
        // Validate the remaining source before inserting our own helper.
        String remaining = source.substring(0, start) + source.substring(end);
        Matcher read = READ.matcher(remaining);
        if (!read.find()) throw new IllegalArgumentException("Canonical Beardifier kernel read required");
        String replacement = "wg_beard_kernel_lookup(" + read.group(1) + ")";
        String rewritten = read.replaceFirst(Matcher.quoteReplacement(replacement));
        if (IDENTIFIER.matcher(rewritten).find()) {
            throw new IllegalArgumentException("Unsupported or duplicate Beardifier kernel use");
        }
        String helper = """
                uint wg_beard_kernel_lookup(int index) {
                    if (index < 0 || index >= 13824
                            || uint(inputBits.length()) != dispatch.count * %du + 13824u) {
                        wg_failed = true;
                        return 0u;
                    }
                    return inputBits[dispatch.count * %du + uint(index)];
                }
                """.formatted(rowWords, rowWords);
        String withHelper = source.substring(0, start) + helper + source.substring(end);
        return new Stage(READ.matcher(withHelper).replaceFirst(Matcher.quoteReplacement(replacement)), rowWords, words);
    }

    private static void validateStride(int rowWords) {
        if (rowWords < 1 || rowWords > 4096) {
            throw new IllegalArgumentException("Beardifier rowWords must be in [1,4096]");
        }
    }

    public record Stage(String source, int rowWords, int[] kernelWords) {
        public Stage {
            validateStride(rowWords);
            if (source == null || source.isBlank()) throw new IllegalArgumentException("Beardifier source required");
            if (kernelWords == null || kernelWords.length != KERNEL_WORDS) {
                throw new IllegalArgumentException("Complete Beardifier kernel required");
            }
            kernelWords = kernelWords.clone();
        }

        @Override public int[] kernelWords() { return kernelWords.clone(); }

        public int[] inputRows(int[] rows) {
            if (rows == null || rows.length == 0 || rows.length % rowWords != 0) {
                throw new IllegalArgumentException("Positive complete Beardifier input rows required");
            }
            int[] input = Arrays.copyOf(rows, Math.addExact(rows.length, KERNEL_WORDS));
            System.arraycopy(kernelWords, 0, input, rows.length, KERNEL_WORDS);
            return input;
        }
    }
}
