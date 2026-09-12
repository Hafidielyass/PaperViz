package dev.paperviz.parsing;

import dev.paperviz.domain.model.Enums.SectionType;

/**
 * One logical section as recovered from the paper, before it is persisted.
 *
 * @param heading  the section title as printed, or null for an untitled block
 * @param bodyText prose with formulas removed
 * @param latex    display formulas, newline-separated, or null when there are none
 */
public record ParsedSection(
        SectionType type,
        String heading,
        String bodyText,
        String latex
) {
}
