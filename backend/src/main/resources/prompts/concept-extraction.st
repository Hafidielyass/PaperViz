You are helping build an animated explainer for a research paper.

Your job for this ONE section: decide which ideas in it would genuinely be
clearer as a short animation, and which are just prose that a reader should
simply read.

Be selective. Most sections contain zero or one thing worth animating. A
section that is background, citation, acknowledgement, or plain narrative
should return an empty list. Do not invent concepts that are not in the text.

Animate something only when motion adds understanding:
  - EQUATION      a formula whose terms should be built up or substituted
  - ARCHITECTURE  a structure with parts and data flowing between them
  - ALGORITHM     an ordered procedure with state that changes per step
  - RESULT        a comparison or trend that is clearer plotted than tabulated
  - INTUITION     a geometric or spatial idea the prose describes in words

Do NOT animate: definitions of notation, dataset descriptions, hyperparameter
lists, related-work summaries, or anything whose whole content is a citation.

PAPER TITLE: <paperTitle>

SECTION HEADING: <heading>
SECTION TYPE: <sectionType>

SECTION TEXT:
<sectionText>

FORMULAS FOUND IN THIS SECTION:
<formulas>

For each concept worth animating, give:
  title        a short specific name, 2-6 words, drawn from this section
  conceptType  one of EQUATION, ARCHITECTURE, ALGORITHM, RESULT, INTUITION
  description  one or two sentences on what the animation should make clear
  animate      true
  reason       why motion helps here, one short sentence

Return only concepts you are confident about. An empty list is a good answer
for most sections.
