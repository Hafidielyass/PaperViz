You are storyboarding a short animation that explains one idea from a research
paper. The animation will be rendered with Manim and narrated aloud.

Constraints that matter, because this becomes real code and real audio:

  - Total length 15 to 45 seconds. Shorter is better.
  - 3 to 6 beats. Each beat is one visual change the viewer can follow.
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

PAPER TITLE: {paperTitle}
SECTION: {heading}

CONCEPT TO ANIMATE: {conceptTitle}
CONCEPT TYPE: {conceptType}
WHAT IT SHOULD MAKE CLEAR: {conceptDescription}

SOURCE TEXT FOR THIS SECTION (background only — this section may describe
several different ideas, and most of it will NOT be about your concept):
{sectionText}

Stay on your concept. Animate "{conceptTitle}" and nothing else. The section
above almost certainly discusses other ideas; those are being storyboarded
separately and must not appear here. Before writing each beat, check that it
is about "{conceptTitle}". If a beat would illustrate a different idea from
the section, drop it and use a beat that develops your own concept further.

RELEVANT FORMULAS:
{formulas}

Produce a storyboard with:
  title             a short title shown at the start, 2-6 words
  summary           one sentence describing the whole animation
  totalSeconds      integer, 15 to 45, equal to the sum of the beat durations
  beats             the ordered list, each with:
      order           1-based position
      seconds         integer, 3 to 12
      visual          what is on screen and what changes, concretely enough
                      that someone could draw it. Name the objects and where
                      they sit relative to each other.
      narration       the words spoken during this beat
      latex           any formula shown in this beat, as LaTeX, or null

The sum of every beat's seconds must equal totalSeconds.
