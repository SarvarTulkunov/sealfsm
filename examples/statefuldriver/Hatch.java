package statefuldriver;

/**
 * NEGATIVE CONTROL for the <em>routing</em> half of the fix, and the reason the
 * parameter did not simply disappear from the code.
 *
 * <p>Widening recognition is only half a change: something then has to walk the
 * newly admitted host, and the two available walks disagree about how much of it
 * to read. A method <b>handed</b> the state computes a successor from it end to
 * end, so its whole body belongs to the transition relation — that is what makes
 * {@code chaindispatch.ShutterLogic}'s {@code return current;} <em>after</em> its
 * chain a real self-loop rather than a stray statement. A driver that only
 * <b>returns</b> the hierarchy type read the state out of a field, and its body is
 * ordinary method plumbing wrapped around a dispatch.
 *
 * <p>{@link HatchDriver#cycle} is that second kind, at its sharpest: it commits by
 * writing the field and then hands the caller the new state back. Two things go
 * wrong at once if the newly admitted host is walked whole:
 *
 * <ul>
 *   <li>the trailing {@code return state;} is read as a produced successor with no
 *       attributable source, and {@code state} is root-typed, so it resolves to
 *       nothing — a fabricated {@code <unknown> --> ?};</li>
 *   <li>the machine is relabelled {@code VALUE_RETURN} on the commit axis, because
 *       the whole-body walk records the commit form it assumes rather than the one
 *       the detector established. The axis exists to stratify the recall table, so
 *       an edge counted under the wrong commit form is a wrong row in a published
 *       result, not a cosmetic slip.</li>
 * </ul>
 *
 * <p>So the parameter keeps a job — it just stops being a recognition test and
 * becomes a statement about <em>scope</em>. Where a producer already identified
 * the discrimination and its real commit, that producer owns the walk.
 *
 * <p>3 states, 6 transitions, 6/6, FIELD_MUTATION, initial {@code Dogged}.
 * {@code examples/barefield} and {@code examples/http2-stream-gemini} are the same
 * control at corpus scale — both are stateful drivers this recognizer now sees,
 * and both must stay byte-identical.
 */
public sealed interface Hatch permits Dogged, Cracked, Gaping {
}
