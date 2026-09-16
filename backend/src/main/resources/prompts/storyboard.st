You are storyboarding a short animation that explains one idea from a research
paper. The animation will be rendered with Manim and narrated aloud.

Constraints that matter, because this becomes real code and real audio:

  - Total length 45 to 75 seconds. This is the main way the idea is explained,
    so give it room.
  - 5 to 8 beats. Each beat is one visual change the viewer can follow.
  - Everything must fit a 16:9 frame with room to breathe. Do not ask for more
    than about 6 distinct objects on screen at once.
  - Narration is spoken, so write it to be heard: short sentences, no
    parentheses, no bullet fragments, no "as shown in Figure 3".
  - Narration for a beat must be speakable within that beat's duration at
    roughly 2.5 words per second. A 5 second beat is about 12 words.
  - Build up, do not dump. Introduce one element at a time and let earlier
    elements stay on screen when they still matter.
  - Every beat must change the picture. Describe only what is NEW or what
    MOVES in that beat. Never restate the previous beat's scene. If you find
    yourself writing "the sentence is still on screen" or repeating an earlier
    visual, merge those beats into one instead.

The two examples below show the SHAPE of good and bad beat descriptions.
They are deliberately about nothing — abstract placeholders. Never reuse their
objects, wording or subject matter. Your beats must describe THIS paper's
concept, using the vocabulary of the section text above.

Well-formed, because each beat changes the picture:
  beat 1  "Object A sits alone at the centre."
  beat 2  "Object B slides in from the right and stops beside A."
  beat 3  "An arrow grows from A to B and B changes colour."

Malformed, because beats 2 and 3 show the same picture:
  beat 2  "A and B are on screen with an arrow between them."
  beat 3  "A and B are still on screen with the arrow visible."

PAPER TITLE: <paperTitle>
SECTION: <heading>

CONCEPT TO ANIMATE: <conceptTitle>
CONCEPT TYPE: <conceptType>
WHAT IT SHOULD MAKE CLEAR: <conceptDescription>

SOURCE TEXT FOR THIS SECTION (background only — this section may describe
several different ideas, and most of it will NOT be about your concept):
<sectionText>

Stay on your concept. Animate "<conceptTitle>" and nothing else. The section
above almost certainly discusses other ideas; those are being storyboarded
separately and must not appear here. Before writing each beat, check that it
is about "<conceptTitle>". If a beat would illustrate a different idea from
the section, drop it and use a beat that develops your own concept further.

RELEVANT FORMULAS:
<formulas>

Produce a storyboard with:
  title             a short title shown at the start, 2-6 words
  summary           one sentence describing the whole animation
  totalSeconds      integer, 45 to 75, equal to the sum of the beat durations
  beats             the ordered list, each with:
      order           1-based position
      seconds         integer, 5 to 14
      visual          what is on screen and what changes, concretely enough
                      that someone could draw it. Name the objects and where
                      they sit relative to each other.
      narration       the words spoken during this beat
      scene           the typed payload described below

THE scene FIELD

Each beat carries a "scene" object with a "kind" and the matching payload.
Prefer a typed kind over FREEFORM whenever one fits — typed scenes are drawn
directly and always render correctly.

  {"kind": "EQUATION", "latex": "E = mc^2"}
      One display formula. Give the formula body ONLY — no dollar signs, no
      \\[ \\] wrapping. Use real LaTeX from the paper where you have it.

  {"kind": "CHART", "chart": {
      "kind": "BAR",
      "xLabel": "Model", "yLabel": "BLEU",
      "categories": ["GNMT", "ConvS2S", "Transformer"],
      "series": [{"name": "EN-DE", "values": [24.6, 25.2, 28.4]}]}}
      Use real numbers from the paper's tables and results. Every series must
      have exactly one value per category. BAR, LINE or SCATTER. Keep it to at
      most 12 categories and 4 series. Never invent numbers — if the paper does
      not give you figures, do not use a CHART beat.

  {"kind": "DIAGRAM", "diagram": {
      "nodes": [{"id": "enc", "label": "Encoder"},
                {"id": "dec", "label": "Decoder"}],
      "edges": [{"from": "enc", "to": "dec", "label": "context"}]}}
      Boxes and arrows, for architectures and data flow. At most 8 boxes. Every
      edge must reference node ids that exist.

  {"kind": "TEXT", "text": "Three ingredients: queries, keys, values"}
      A short line on screen. Under 25 words.

  {"kind": "FREEFORM", "description": "..."}
      Only when nothing above fits. These cost more and are less reliable.

At least one beat must be a CHART, DIAGRAM or EQUATION. A part built only from
text captions is a slideshow, and the reader already has the prose beside the
video.

Reach for EQUATION when the paper states a formula, and for CHART when it
reports numbers worth comparing. Where the paper gives no figures, a DIAGRAM of
the pieces and how data moves between them is almost always available.

The sum of every beat's seconds must equal totalSeconds.
