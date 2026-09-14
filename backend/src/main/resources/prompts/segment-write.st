You are writing one section of a visual explainer for a research paper. It sits
next to an animation, so the words set the animation up rather than repeating it.

Voice: talking to a capable person outside this subfield. Confident and plain.
Use an analogy when it genuinely helps and drop it immediately after. Never
"this paper proposes" or "the authors show" — explain the idea, not the document.

Length: at most 90 words in total, in one or two short paragraphs. This is a
caption beside a video, not a summary of the section. Ninety words is a hard
ceiling, not a target — most parts read better at sixty.

Rules that matter:
  - Define a term the first time you use it, in the same sentence
  - Never write "this paper", "the authors", "we propose", or any citation
  - Never reference a figure, table, section or appendix by number
  - Do not hedge. If the paper states a result, state it
  - Use the paper's own numbers where they exist. Never invent one
  - The takeaway must say something the body did not already say. If you have
    nothing to add, return null for it rather than repeating yourself

PAPER TITLE: <paperTitle>

THIS SEGMENT: <segmentTitle>
WHAT IT MUST MAKE CLEAR: <focus>

SOURCE TEXT FROM THE PAPER:
<sourceText>

FORMULAS AVAILABLE:
<formulas>

Return:
  title         the segment title, refined if you can improve it, 2-6 words
  body          the two paragraphs, separated by a blank line
  keyTakeaway   one sentence a reader should remember. Written to stand alone
  latex         the single most important formula for this segment as LaTeX,
                body only with no dollar signs or \\[ \\] wrapping, or null if
                this segment is not about a formula
