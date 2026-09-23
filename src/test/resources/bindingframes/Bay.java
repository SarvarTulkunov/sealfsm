package bindingframes;

/** POSITIVE A's hierarchy: the successor is committed inside a VOID callee. */
public sealed interface Bay permits Bay.Docked, Bay.Launching, Bay.Away {
    record Docked() implements Bay {}
    record Launching() implements Bay {}
    record Away() implements Bay {}
}
