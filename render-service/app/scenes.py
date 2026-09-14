"""Turns a typed storyboard into Manim animation.

Every function here is deterministic. The model chose *what* to show; nothing in
this module asks it *how*, which is the whole reason charts and equations render
reliably instead of failing on a hallucinated API call.

Two rules run through it:

  Things move. A fade-in followed by a hold is a slideshow with extra steps.
  Bars grow from the axis, arrows draw themselves between boxes, equations
  write on and then point at the term that matters.

  Nothing leaves the frame. Everything is built inside a fixed content box and
  scaled down to fit, because Manim will happily draw past the edge and an
  off-screen chart looks exactly like a broken render.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from typing import Any, Callable

from manim import (
    Arrow,
    BarChart,
    Circumscribe,
    Create,
    FadeIn,
    FadeOut,
    GrowArrow,
    Rectangle,
    Scene,
    Text,
    VGroup,
    Write,
)
from manim import DOWN, LEFT, RIGHT, UP
from manim import BLUE, GREY_B, TEAL, WHITE, YELLOW

log = logging.getLogger(__name__)

# 16:9 with a margin, so nothing ever touches the frame edge.
CONTENT_WIDTH = 12.0
CONTENT_HEIGHT = 6.0

TITLE_SIZE = 34
BODY_SIZE = 26
LABEL_SIZE = 20

SERIES_COLOURS = [BLUE, TEAL, YELLOW, GREY_B]


@dataclass
class Beat:
    """A built visual plus how it should come alive.

    `play` receives the scene and the seconds available, and is responsible for
    filling that time. Splitting construction from performance is what lets a
    chart grow its bars while an equation writes itself.
    """

    mobject: Any
    play: Callable[[Scene, Any, float], None] = field(default=None)


def fit(mobject, width: float = CONTENT_WIDTH, height: float = CONTENT_HEIGHT):
    """Scales a mobject down until it fits the content box. Never scales up."""
    if mobject.width > width:
        mobject.scale_to_fit_width(width)
    if mobject.height > height:
        mobject.scale_to_fit_height(height)
    return mobject


def _hold(scene: Scene, seconds: float, used: float) -> None:
    """Spends whatever time the animations did not."""
    scene.wait(max(0.3, seconds - used))


# --------------------------------------------------------------------------- #
# builders
# --------------------------------------------------------------------------- #

def build_text(spec: dict[str, Any]) -> Beat | None:
    text = (spec.get("text") or "").strip()
    if not text:
        return None
    mobject = fit(Text(text, font_size=BODY_SIZE, color=WHITE), width=CONTENT_WIDTH - 1)

    def play(scene: Scene, m, seconds: float) -> None:
        intro = min(1.0, seconds * 0.3)
        scene.play(Write(m), run_time=intro)
        _hold(scene, seconds, intro)

    return Beat(mobject, play)


def build_equation(spec: dict[str, Any]) -> Beat | None:
    """Writes the formula on, then draws attention to it.

    Falls back to monospace text when TeX will not compile: a paper can use a
    macro our LaTeX set does not ship, and a plain-text formula is a far better
    outcome than losing the whole video.
    """
    latex = (spec.get("latex") or "").strip()
    if not latex:
        return None

    from manim import MathTex  # imported late so a TeX failure cannot break import

    try:
        mobject = fit(MathTex(latex, font_size=46, color=WHITE))
        typeset = True
    except Exception as exc:  # noqa: BLE001 - any TeX failure is a fallback case
        log.warning("MathTex failed for %r (%s); falling back to plain text", latex, exc)
        mobject = fit(Text(latex, font_size=BODY_SIZE, color=WHITE, font="monospace"),
                      width=CONTENT_WIDTH - 1)
        typeset = False

    def play(scene: Scene, m, seconds: float) -> None:
        intro = min(1.6, seconds * 0.35)
        scene.play(Write(m), run_time=intro)
        used = intro
        if typeset and seconds - used > 2.0:
            scene.play(Circumscribe(m, color=TEAL, run_time=1.2))
            used += 1.2
        _hold(scene, seconds, used)

    return Beat(mobject, play)


def build_chart(spec: dict[str, Any]) -> Beat | None:
    """A bar chart whose bars grow from the axis.

    Only BAR is drawn as a true chart; LINE and SCATTER fall back to bars rather
    than risking an axis configuration that runs off frame. Getting the numbers
    on screen legibly matters more than the mark type.
    """
    chart = spec.get("chart") or {}
    categories = [str(c) for c in (chart.get("categories") or [])]
    series = chart.get("series") or []
    if not categories or not series:
        return None

    values = [float(v) for v in (series[0].get("values") or [])]
    if len(values) != len(categories):
        log.warning("chart series length %d != categories %d", len(values), len(categories))
        return None

    y_min, y_max, step = nice_axis(values)

    bars = BarChart(
        values=[y_min] * len(values),          # start flat; grow to the real numbers
        bar_names=categories,
        y_range=[y_min, y_max, step],
        y_length=5,
        x_length=9,
        bar_colors=[SERIES_COLOURS[0].to_hex()] * len(values),
    )

    group = VGroup(bars)

    if chart.get("yLabel"):
        label = Text(str(chart["yLabel"]), font_size=LABEL_SIZE, color=GREY_B)
        label.rotate(math.pi / 2).next_to(bars, LEFT, buff=0.3)
        group.add(label)
    if chart.get("xLabel"):
        label = Text(str(chart["xLabel"]), font_size=LABEL_SIZE, color=GREY_B)
        label.next_to(bars, DOWN, buff=0.5)
        group.add(label)

    # A second series becomes a caption rather than grouped bars: grouping needs
    # careful spacing, and an unreadable chart is worse than one honest series.
    if len(series) > 1:
        names = ", ".join(str(s.get("name")) for s in series[1:] if s.get("name"))
        if names:
            note = Text(f"(also: {names})", font_size=LABEL_SIZE - 2, color=GREY_B)
            note.next_to(group, UP, buff=0.25)
            group.add(note)

    fit(group)

    def play(scene: Scene, m, seconds: float) -> None:
        intro = min(1.0, seconds * 0.2)
        scene.play(FadeIn(m), run_time=intro)
        grow = min(2.2, max(1.0, seconds * 0.35))
        scene.play(bars.animate.change_bar_values(values), run_time=grow)
        used = intro + grow

        # Point at the tallest bar once there is time to notice it.
        if seconds - used > 2.0 and values:
            tallest = bars.bars[values.index(max(values))]
            scene.play(Circumscribe(tallest, color=YELLOW, run_time=1.2))
            used += 1.2
        _hold(scene, seconds, used)

    return Beat(group, play)


def build_diagram(spec: dict[str, Any]) -> Beat | None:
    """Boxes appear in turn, then arrows draw themselves between them."""
    diagram = spec.get("diagram") or {}
    nodes = diagram.get("nodes") or []
    edges = diagram.get("edges") or []
    if not nodes:
        return None

    boxes: dict[str, VGroup] = {}
    row = VGroup()

    for node in nodes:
        node_id = str(node.get("id") or "")
        label = Text(str(node.get("label") or node_id), font_size=LABEL_SIZE, color=WHITE)
        box = Rectangle(
            width=max(label.width + 0.6, 1.8),
            height=max(label.height + 0.5, 0.9),
            color=BLUE,
        )
        label.move_to(box.get_center())
        group = VGroup(box, label)
        boxes[node_id] = group
        row.add(group)

    row.arrange(RIGHT, buff=1.1)

    arrows = VGroup()
    for edge in edges:
        source = boxes.get(str(edge.get("from") or ""))
        target = boxes.get(str(edge.get("to") or ""))
        if source is None or target is None:
            continue
        arrows.add(Arrow(source.get_right(), target.get_left(), buff=0.12, color=GREY_B))

    whole = VGroup(row, arrows)
    fit(whole)

    def play(scene: Scene, m, seconds: float) -> None:
        # Budget: boxes in sequence, then arrows, then hold.
        box_time = min(0.5, max(0.2, seconds * 0.35 / max(len(row), 1)))
        used = 0.0
        for box in row:
            scene.play(Create(box), run_time=box_time)
            used += box_time
        if len(arrows) > 0:
            arrow_time = min(0.6, max(0.25, seconds * 0.3 / len(arrows)))
            for arrow in arrows:
                scene.play(GrowArrow(arrow), run_time=arrow_time)
                used += arrow_time
        _hold(scene, seconds, used)

    return Beat(whole, play)


def build_freeform(spec: dict[str, Any]) -> Beat | None:
    """No template matched, so show the intent as text.

    An honest placeholder until code generation lands: obviously a fallback on
    screen, rather than silently rendering nothing.
    """
    description = (spec.get("description") or "").strip()
    if not description:
        return None
    mobject = fit(Text(description, font_size=BODY_SIZE - 4, color=GREY_B, line_spacing=1.2),
                  width=CONTENT_WIDTH - 1)

    def play(scene: Scene, m, seconds: float) -> None:
        intro = min(1.0, seconds * 0.25)
        scene.play(FadeIn(m, shift=UP * 0.3), run_time=intro)
        _hold(scene, seconds, intro)

    return Beat(mobject, play)


def nice_axis(values: list[float], target_ticks: int = 4) -> tuple[float, float, float]:
    """Picks an axis range and step a person would have chosen.

    Dividing the data range by four gives ticks like 16.330 and 24.495, which
    look like a bug even when the bars are correct. This snaps the step to a
    1/2/5 x 10^n value and rounds the bounds outward to a multiple of it.
    """
    top = max(values + [0.0])
    bottom = min(values + [0.0])
    if top == bottom:
        top = bottom + 1.0

    span = top - bottom
    rough = span / max(target_ticks, 1)
    magnitude = 10 ** math.floor(math.log10(rough)) if rough > 0 else 1.0
    normalised = rough / magnitude

    if normalised <= 1:
        step = 1 * magnitude
    elif normalised <= 2:
        step = 2 * magnitude
    elif normalised <= 5:
        step = 5 * magnitude
    else:
        step = 10 * magnitude

    axis_min = math.floor(min(bottom, 0.0) / step) * step
    axis_max = math.ceil(top / step) * step
    if axis_max <= top:
        axis_max += step
    return axis_min, axis_max, step


BUILDERS = {
    "TEXT": build_text,
    "EQUATION": build_equation,
    "CHART": build_chart,
    "DIAGRAM": build_diagram,
    "FREEFORM": build_freeform,
}


def build_beat(spec: dict[str, Any]) -> Beat | None:
    """Builds one beat, or None when there is nothing to draw."""
    if not spec:
        return None
    kind = (spec.get("kind") or "FREEFORM").upper()
    builder = BUILDERS.get(kind, build_freeform)
    try:
        return builder(spec)
    except Exception:  # noqa: BLE001 - one bad beat must not lose the whole video
        log.exception("failed to build a %s beat", kind)
        return None


class StoryboardScene(Scene):
    """Plays a storyboard: a title card, then one shot per beat.

    Beat durations arrive already reconciled against the measured narration, so
    the renderer never has to know about audio — it just fills the time it is
    given.
    """

    storyboard: dict[str, Any] = {}

    def construct(self) -> None:
        beats = self.storyboard.get("beats") or []
        title = (self.storyboard.get("title") or "").strip()

        if title:
            card = fit(Text(title, font_size=TITLE_SIZE, color=WHITE), width=CONTENT_WIDTH - 2)
            self.play(Write(card), run_time=0.9)
            self.wait(0.9)
            self.play(FadeOut(card, shift=UP * 0.4), run_time=0.4)

        previous = None
        for beat_spec in beats:
            seconds = float(beat_spec.get("seconds") or 4)
            spec = beat_spec.get("scene") or {}
            beat = build_beat(spec)

            if beat is None:
                # Nothing renderable — hold so the narration still lands.
                self.wait(seconds)
                continue

            if previous is not None:
                self.play(FadeOut(previous, shift=DOWN * 0.3), run_time=0.35)
                seconds = max(0.5, seconds - 0.35)

            beat.play(self, beat.mobject, seconds)
            previous = beat.mobject

        if previous is not None:
            self.play(FadeOut(previous), run_time=0.5)
