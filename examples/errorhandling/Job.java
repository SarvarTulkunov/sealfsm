package examples.errorhandling;

/**
 * Sealed root for the exceptional/iterative control-flow micro-benchmark
 * (finding F6). The transition function in {@link JobMachine} produces next
 * states from <em>inside</em> a {@code try}/{@code catch} and a {@code while}
 * loop — constructs the walker previously refused to descend, silently dropping
 * the error transition and the loop-body producer. The walk must now recover:
 *
 * <ul>
 *   <li>{@code Running → Done} (normal path, inside the {@code try} body);</li>
 *   <li>{@code Running → Failed} guarded by {@code exception} (the {@code catch});</li>
 *   <li>{@code Waiting → Running} from the {@code while}-loop body.</li>
 * </ul>
 */
public sealed interface Job permits Running, Done, Failed, Waiting {}
