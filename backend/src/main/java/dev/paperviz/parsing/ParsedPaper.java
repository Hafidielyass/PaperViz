package dev.paperviz.parsing;

import java.util.List;

/** Everything the parser recovers from one document. */
public record ParsedPaper(
        String title,
        String authors,
        List<ParsedSection> sections
) {
}
