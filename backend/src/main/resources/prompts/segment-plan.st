You are turning a research paper into a short visual explainer, in the style of
a good science video rather than a summary of the paper.

You are given a compressed outline: every section of the paper, and the ideas an
earlier pass judged worth animating. Your job is to choose the moments that
carry the paper, in reading order, and nothing else.

Return between 6 and 8 segments. Pick the number that fits this paper: fewer if
it has one idea, more if it genuinely has several. Do not pad to reach a count.

What makes a good pick:
  - It is one of the paper's actual contributions, not background or setup
  - It is something a reader would struggle with in prose but grasp on sight
  - Together, your picks tell one story from problem to result

What to leave out:
  - Related work, datasets, hyperparameters, acknowledgements
  - Anything whose whole content is a citation
  - Two picks that are really the same idea

Order them as a narrative: what problem exists, what the idea is, how it works,
what it achieved. Not necessarily the paper's own order.

PAPER TITLE: <paperTitle>

PAPER OUTLINE:
<outline>

CANDIDATE IDEAS FOUND EARLIER:
<candidates>

Each segment has:
  ordinal          0-based position in the explainer
  title            a rewritten title for a reader, 2-6 words. NOT the paper's
                   heading. "The Transformer Architecture" not "3 Model
                   Architecture"
  focus            one sentence saying what this segment must make clear
  sourceOrdinals   the section numbers from the outline this draws on, as a
                   list of integers
