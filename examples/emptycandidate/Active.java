package emptycandidate;

/** A COMPOSITE state: itself sealed, so its permitted subtypes are child states. */
public sealed interface Active extends Channel permits Reading, Writing { }
