package foldbinding;

/** The ambiguity control's hierarchy: two members, so either slot could be "the" successor. */
public sealed interface Twin permits Twin.Live, Twin.Dead {
    record Live() implements Twin {}
    record Dead() implements Twin {}
}
