public final class LcpDemo {
    private LcpDemo() {
    }

    public static void main(String[] args) {
        LcpAutomaton automaton = new LcpAutomaton();

        apply(automaton, LcpEvent.OPEN);
        apply(automaton, LcpEvent.UP);
        apply(automaton, LcpEvent.RCA);
        apply(automaton, LcpEvent.RCR_PLUS);

        if (!(automaton.state() instanceof Opened)) {
            throw new AssertionError("Expected Opened state");
        }

        apply(automaton, LcpEvent.CLOSE);
        apply(automaton, LcpEvent.RTA);

        if (!(automaton.state() instanceof Closed)) {
            throw new AssertionError("Expected Closed state");
        }

        System.out.println("Final state: " + automaton.state().getClass().getSimpleName());
    }

    private static void apply(LcpAutomaton automaton, LcpEvent event) {
        LcpTransition transition = automaton.on(event);
        System.out.printf("%-9s -> %-9s actions=%s%n",
                event,
                transition.state().getClass().getSimpleName(),
                transition.actions());
    }
}
