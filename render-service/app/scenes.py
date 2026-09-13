"""Turns a typed storyboard into Manim mobjects.

Every function here is deterministic. The model chose *what* to show; nothing in
this module asks it *how*, which is the whole reason charts and equations render
reliably instead of failing on a hallucinated API call.

Layout rule throughout: build inside a fixed content box and scale down to fit.
Manim will happily draw past the frame edge, and an off-screen chart looks
exactly like a broken render.
"""

from __future__ import annotations

import logging
from typing import Any

from manim import (
    Arrow,
    BarChart,
    Create,
    FadeIn,
    FadeOut,
    Rectangle,
    Scene,
    Text,
    VGroup,
    Write,
    config,
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


def fit(mobject, width: float = CONTENT_WIDTH, height: float = CONTENT_HEIGHT):
    """Scales a mobject down until it fits the content box. Never scales up."""
    if mobject.width > width:
        mobject.scale_to_fit_width(width)
    if mobject.height > height:
        mobject.scale_to_fit_height(height)
    return mobject


def build_text(scene_spec: dict[str, Any]):
    text = (scene_spec.get("text") or "").strip()
    if not text:
        return None
    return fit(Text(text, font_size=BODY_SIZE, color=WHITE))


def build_equation(scene_spec: dict[str, Any]):
    """Renders LaTeX, falling back to monospace text if TeX will not compile.

    A paper can contain a macro our curated TeX set does not ship. Showing the
    formula as plain text is a poor result but a far better one than failing the
    whole video.
    """
    latex = (scene_spec.get("latex") or "").strip()
    if not latex:
        return None

    # Imported lazily so a TeX failure cannot break module import.
    from manim import MathTex

    try:
        return fit(MathTex(latex, font_size=44, color=WHITE))
    except Exception as exc:  # noqa: BLE001 - any TeX failure is a fallback case
        log.warning("MathTex failed for %r (%s); falling back to plain text", latex, exc)
        return fit(Text(latex, font_size=BODY_SIZE, color=WHITE, font="monospace"))


def nice_axis(values: list[float], target_ticks: int = 4) -> tuple[float, float, float]:
    """Picks an axis range and step a person would have chosen.

    Dividing the data range by four gives ticks like 16.330 and 24.495, which
    look like a bug even when the bars are correct. This snaps the step to a
    1/2/5 x 10^n value and rounds the bounds outward to a multiple of it.
    """
    import math

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
    # One extra step of headroom so the tallest bar is not flush with the top.
    axis_max = math.ceil(top / step) * step
    if axis_max <= top:
        axis_max += step

    return axis_min, axis_max, step


def build_chart(scene_spec: dict[str, Any]):
    """A bar chart from explicit categories and series.

    Only BAR is drawn as a true chart; LINE and SCATTER fall back to bars rather
    than risking an axis configuration that runs off frame. Getting the numbers
    on screen legibly matters more than the mark type.
    """
    chart = scene_spec.get("chart") or {}
    categories = [str(c) for c in (chart.get("categories") or [])]
    series = chart.get("series") or []
    if not categories or not series:
        return None

    first = series[0]
    values = [float(v) for v in (first.get("values") or [])]
    if len(values) != len(categories):
        log.warning("chart series length %d != categories %d", len(values), len(categories))
        return None

    y_min, y_max, step = nice_axis(values)

    bars = BarChart(
        values=values,
        bar_names=categories,
        y_range=[y_min, y_max, step],
        y_length=5,
        x_length=9,
        bar_colors=[SERIES_COLOURS[0].to_hex()] * len(values),
    )

    group = VGroup(bars)

    y_label = chart.get("yLabel")
    if y_label:
        label = Text(str(y_label), font_size=LABEL_SIZE, color=GREY_B)
        label.rotate(90 * 3.14159 / 180).next_to(bars, LEFT, buff=0.3)
        group.add(label)

    x_label = chart.get("xLabel")
    if x_label:
        label = Text(str(x_label), font_size=LABEL_SIZE, color=GREY_B)
        label.next_to(bars, DOWN, buff=0.5)
        group.add(label)

    # A second series is shown as a legend note rather than grouped bars:
    # grouped bars need careful spacing, and an unreadable chart is worse
    # than one honest series plus a caption.
    if len(series) > 1:
        names = ", ".join(str(s.get("name")) for s in series[1:] if s.get("name"))
        if names:
            note = Text(f"(also: {names})", font_size=LABEL_SIZE - 2, color=GREY_B)
            note.next_to(group, UP, buff=0.25)
            group.add(note)

    return fit(group)


def build_diagram(scene_spec: dict[str, Any]):
    """Labelled boxes joined by arrows, laid out left to right."""
    diagram = scene_spec.get("diagram") or {}
    nodes = diagram.get("nodes") or []
    edges = diagram.get("edges") or []
    if not nodes:
        return None

    boxes: dict[str, VGroup] = {}
    row = VGroup()

    for node in nodes:
        node_id = str(node.get("id") or "")
        label_text = str(node.get("label") or node_id)
        label = Text(label_text, font_size=LABEL_SIZE, color=WHITE)
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

    return fit(VGroup(row, arrows))


def build_freeform(scene_spec: dict[str, Any]):
    """No template matched, so show the intent as text.

    This is the honest placeholder until LLM code generation lands: it is
    obviously a fallback on screen, rather than silently rendering nothing.
    """
    description = (scene_spec.get("description") or "").strip()
    if not description:
        return None
    return fit(Text(description, font_size=BODY_SIZE - 4, color=GREY_B, line_spacing=1.2),
               width=CONTENT_WIDTH - 1)


BUILDERS = {
    "TEXT": build_text,
    "EQUATION": build_equation,
    "CHART": build_chart,
    "DIAGRAM": build_diagram,
    "FREEFORM": build_freeform,
}


def build_beat(scene_spec: dict[str, Any]):
    """Returns a mobject for one beat, or None when there is nothing to draw."""
    if not scene_spec:
        return None
    kind = (scene_spec.get("kind") or "FREEFORM").upper()
    builder = BUILDERS.get(kind, build_freeform)
    try:
        return builder(scene_spec)
    except Exception:  # noqa: BLE001 - one bad beat must not lose the whole video
        log.exception("failed to build a %s beat", kind)
        return None


class StoryboardScene(Scene):
    """Plays a storyboard: a title card, then one shot per beat.

    Beat durations come from the storyboard, which the backend has already
    reconciled against the narration length, so the video and the voiceover
    stay in step without the renderer needing to know about audio.
    """

    storyboard: dict[str, Any] = {}

    def construct(self) -> None:
        beats = self.storyboard.get("beats") or []
        title = (self.storyboard.get("title") or "").strip()

        if title:
            card = fit(Text(title, font_size=TITLE_SIZE, color=WHITE), width=CONTENT_WIDTH - 2)
            self.play(Write(card), run_time=0.8)
            self.wait(1.0)
            self.play(FadeOut(card), run_time=0.4)

        previous = None
        for index, beat in enumerate(beats):
            seconds = float(beat.get("seconds") or 4)
            spec = beat.get("scene") or {}
            mobject = build_beat(spec)

            if mobject is None:
                # Nothing renderable — hold the frame so narration still fits.
                self.wait(seconds)
                continue

            kind = (spec.get("kind") or "").upper()
            enter = Create if kind == "DIAGRAM" else FadeIn
            enter_time = min(0.9, seconds * 0.25)

            if previous is not None:
                self.play(FadeOut(previous), run_time=0.3)

            self.play(enter(mobject), run_time=enter_time)
            self.wait(max(0.3, seconds - enter_time))
            previous = mobject

        if previous is not None:
            self.play(FadeOut(previous), run_time=0.4)
