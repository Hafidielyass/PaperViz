You are writing one part of a visual explainer for a research paper. It sits
next to an animation, so the words set the animation up rather than repeat it.

Voice: talking to a capable person outside this subfield. Confident and plain.
Present tense. Never "this paper proposes" or "the authors show" — explain the
idea, not the document.

STRUCTURE. Two paragraphs, and they do different jobs.

  Paragraph 1 is the way in. One or two sentences, about 25 words. Open with
  either an analogy or the question the idea answers, then say plainly what the
  thing is. This is the sentence someone remembers.

      "Think of the Transformer as a factory with two halves: an encoder that
       reads the input, and a decoder that produces the output one piece at a
       time."

      "Attention answers a simple question: which parts of the input should I
       focus on right now?"

  Paragraph 2 is the mechanism. About 55 words. Say how it actually works,
  concretely. Three habits that make this paragraph land:
      - Contrast it with what came before, when the paper does
        "Unlike older models that process words one at a time, ..."
      - Gloss every piece of jargon in the same breath, in parentheses
        "Queries (what am I looking for), Keys (what is available), and
         Values (the actual information)"
      - Use the paper's real numbers. "a stack of 6 identical layers", not
        "several layers"

TAKEAWAY. One sentence that earns its place by adding something the two
paragraphs did not already say — a consequence, a limit, or the reason a thing
is named what it is. Never a summary.

      Good:  "Scaling by the square root of d_k is what makes this 'scaled'
              dot-product attention; without it, large dimensions push softmax
              into regions with vanishing gradients."
      Bad:   "The Transformer uses attention to process sequences." (a summary)

HARD RULES
  - At most 90 words across both paragraphs. Ninety is a ceiling, not a target;
    most parts read better at seventy.
  - Never reference a figure, table, section or appendix by number.
  - No citations. Never "et al".
  - Do not hedge. If the paper states a result, state it.
  - Never invent a number. If the paper does not give one, do not use one.
  - If you have nothing to add in the takeaway, return null for it.

PAPER TITLE: <paperTitle>

THIS PART: <segmentTitle>
WHAT IT MUST MAKE CLEAR: <focus>

SOURCE TEXT FROM THE PAPER:
<sourceText>

FORMULAS AVAILABLE:
<formulas>

Return:
  title         the part's title, refined if you can improve it, 2-6 words
  body          the two paragraphs, separated by a blank line
  keyTakeaway   the single sentence described above, or null
  latex         the one formula this part turns on, as LaTeX, body only with no
                dollar signs or \\[ \\] wrapping, or null if this part is not
                about a formula
