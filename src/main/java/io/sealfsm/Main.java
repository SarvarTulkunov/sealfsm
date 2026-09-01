package io.sealfsm;

import io.sealfsm.model.Candidate;
import io.sealfsm.model.CommitEvidence;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
import io.sealfsm.model.Transition;
import io.sealfsm.serialize.DotSerializer;
import io.sealfsm.serialize.ScxmlSerializer;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Command-line driver.
 *
 * <pre>
 *   java -jar sealfsm.jar --src path/to/src [--src more/src] \
 *        [--out out-dir] [--format dot|scxml|both] [--classpath cp] \
 *        [--quiet] [--explain]
 * </pre>
 */
public final class Main {

    private enum Format { DOT, SCXML, BOTH }

    public static void main(String[] args) {
        List<String> sources = new ArrayList<>();
        List<String> classpath = new ArrayList<>();
        Path out = Path.of("out");
        Format format = Format.BOTH;
        boolean quiet = false;
        boolean explain = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--src" -> sources.add(require(args, ++i, "--src"));
                case "--out" -> out = Path.of(require(args, ++i, "--out"));
                case "--format" -> format = Format.valueOf(require(args, ++i, "--format").toUpperCase());
                case "--classpath", "--cp" ->
                        classpath.addAll(splitClasspath(require(args, ++i, "--classpath")));
                case "--quiet" -> quiet = true;
                case "--explain" -> explain = true;
                case "-h", "--help" -> { printUsage(); return; }
                default -> { System.err.println("Unknown argument: " + args[i]); printUsage(); System.exit(2); }
            }
        }
        if (sources.isEmpty()) {
            System.err.println("error: at least one --src is required");
            printUsage();
            System.exit(2);
        }

        Launcher launcher = new Launcher();
        for (String s : sources) launcher.addInputResource(s);
        launcher.getEnvironment().setComplianceLevel(17);
        // noClasspath stays on even WITH --classpath. The two are not alternatives:
        // it is what lets an incomplete classpath degrade into a guessed qualified
        // name instead of aborting the build, and no realistic invocation supplies
        // every transitive dependency. --classpath narrows the set of references
        // that have to be guessed; the audit reports whatever is left.
        launcher.getEnvironment().setNoClasspath(true);
        if (!classpath.isEmpty()) {
            launcher.getEnvironment().setSourceClasspath(classpath.toArray(new String[0]));
        }
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        ExtractionResult result = new Analyzer().explaining(explain).analyze(model);

        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            System.err.println("error: cannot create output dir " + out + ": " + e.getMessage());
            System.exit(1);
        }

        DotSerializer dot = new DotSerializer();
        ScxmlSerializer scxml = new ScxmlSerializer();
        int written = 0;
        for (StateMachine m : result.machines()) {
            try {
                if (format == Format.DOT || format == Format.BOTH) {
                    Files.writeString(out.resolve(m.name() + ".dot"), dot.serialize(m));
                    written++;
                }
                if (format == Format.SCXML || format == Format.BOTH) {
                    Files.writeString(out.resolve(m.name() + ".scxml"), scxml.serialize(m));
                    written++;
                }
            } catch (IOException e) {
                System.err.println("error writing " + m.name() + ": " + e.getMessage());
            }
        }

        printSummary(result, out, written, quiet);
        if (result.isEmpty()) System.exit(1);
    }

    private static void printSummary(ExtractionResult result, Path out, int written, boolean quiet) {
        System.out.println();
        System.out.printf("Found %d state machine(s); wrote %d file(s) to %s%n",
                result.machines().size(), written, out.toAbsolutePath());
        System.out.println("-".repeat(112));
        System.out.printf("%-22s %-20s %6s %6s %9s  %-28s %s%n",
                "MACHINE", "DISPATCH", "STATES", "TRANS", "RESOLVED", "COMMIT", "SUCCESSOR FORMS");
        for (StateMachine m : result.machines()) {
            System.out.printf("%-22s %-20s %6d %6d %9s  %-28s %s%n",
                    truncate(m.name(), 22),
                    m.encoding(),
                    m.allStates().size(),
                    m.transitions().size(),
                    m.resolvedTransitionCount() + "/" + m.transitions().size(),
                    commitList(m),
                    formList(m));
        }
        printTierNote(result);
        printCandidates(result);
        printUnreadDeclarationNote(result);
        printEncodingRollup(result);
        printExplanations(result);
        if (!quiet && !result.diagnostics().isEmpty()) {
            System.out.println("-".repeat(72));
            System.out.println("Diagnostics:");
            result.diagnostics().forEach(d -> System.out.println("  " + d));
        }
    }

    /**
     * The classifier's reasoning for every root it rejected ({@code --explain}).
     *
     * <p>Printed regardless of {@code --quiet} for the same reason the unread-
     * declaration footnote is: {@code --quiet} suppresses findings ABOUT the run,
     * and this was explicitly asked for. A rejection otherwise reports only the
     * predicate that ran last, so a reader cannot tell a hierarchy that failed
     * one conjunct of one predicate from one that matched no recognizer at all.
     */
    private static void printExplanations(ExtractionResult result) {
        if (result.explanations().isEmpty()) return;
        System.out.println();
        System.out.println("-".repeat(112));
        System.out.println("Why each rejected sealed root was rejected (--explain):");
        for (ExtractionResult.Explanation e : result.explanations()) {
            System.out.println();
            System.out.println("  " + e.where());
            for (String predicate : e.predicates()) {
                System.out.println("      - " + predicate);
            }
        }
    }

    /**
     * Split one {@code --classpath} value on the platform separator, so the flag
     * takes the shape every other Java tool takes it in ({@code a.jar:b.jar}) and
     * is also repeatable for callers that would rather pass entries one at a time.
     */
    private static List<String> splitClasspath(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    /**
     * A footnote for the machines whose row reads {@code 0/n}, printed only when
     * there are any.
     *
     * <p>Tier 1 and Tier 2 produce the same shape of row and make different
     * claims. {@code 0/4} on its own reads as a failure; what it actually says
     * here is that the dispatch and the commit are both established and that every
     * successor is explicitly unknown — a stated boundary, with four states that
     * are exact regardless. The line also names the evidence, because "the commit
     * was observed at the dispatch" and "the commit was proven by opening one
     * callee" are two strengths and pooling them would make a later gap
     * unattributable.
     *
     * <p>Printed unconditionally of {@code --quiet} for the reason the unread-
     * declaration footnote is: {@code --quiet} suppresses findings <em>about</em>
     * the run, and this is a qualification on the numbers directly above.
     */
    private static void printTierNote(ExtractionResult result) {
        var tier2 = result.machines().stream().filter(StateMachine::isDetectedEmpty).toList();
        var viaCallee = result.machines().stream()
                .filter(m -> m.commitEvidence() != CommitEvidence.DIRECT).toList();
        if (tier2.isEmpty() && viaCallee.isEmpty()) return;
        System.out.println();
        for (StateMachine m : tier2) {
            System.out.printf("  ! %s: TIER 2 — dispatch present, commit proven (%s), no successor "
                            + "resolved.%n", m.name(), m.commitEvidence());
            System.out.printf("    Its %d state(s) are exact; each of the %d dispatched arm(s) is "
                            + "recorded as an%n", m.allStates().size(), m.transitions().size());
            System.out.println("    unresolved edge with a known source state, never as an empty "
                    + "relation.");
        }
        for (StateMachine m : viaCallee) {
            if (tier2.contains(m)) continue;
            System.out.printf("  ! %s: commit evidence %s — proven by opening one callee body "
                            + "(k = 1 probe).%n", m.name(), m.commitEvidence());
        }
    }

    /**
     * Hierarchies the tool refuses to call machines and whose states it reports
     * anyway — Tier 3.
     *
     * <p>Not folded into the diagnostics listing, and printed regardless of
     * {@code --quiet}, because a candidate is a <em>result</em> rather than a
     * finding about the run: it is the answer to "what are the states of this
     * hierarchy?", which the tool can give exactly even where it can prove nothing
     * about the transitions. Refusing to call a hierarchy a machine and refusing
     * to say what its states are were the same refusal before this channel
     * existed, and they are two different claims. The reason each one was rejected
     * stays in the diagnostics, where it belongs.
     */
    private static void printCandidates(ExtractionResult result) {
        if (result.candidates().isEmpty()) return;
        System.out.println();
        System.out.println("-".repeat(112));
        System.out.printf("Candidate hierarchies — dispatch present, commit NOT proven, so not "
                + "reported as machines (%d):%n", result.candidates().size());
        for (Candidate c : result.candidates()) {
            System.out.printf("  %-40s %2d state(s)  %s%n",
                    truncate(c.qualifiedName(), 40),
                    c.allStates().size(),
                    c.allStates().stream().map(State::id).collect(Collectors.joining(", ")));
        }
        System.out.println("    States come from the permits clause and are exact. No transition "
                + "relation is claimed.");
    }

    /**
     * A footnote naming the states whose declarations were never read, printed
     * only when there are any.
     *
     * <p>The table above cannot carry this: it reports a resolved count, and an
     * edge matched through a guessed qualified name counts there exactly like one
     * matched against a declaration. That is the whole difficulty — the score
     * looks the same either way, so the condition has to be said out loud next to
     * it. Printed unconditionally of {@code --quiet}, which suppresses
     * diagnostics: this is not a diagnostic but a qualification on the numbers
     * directly above, and suppressing it would leave the numbers looking stronger
     * than they are.
     */
    private static void printUnreadDeclarationNote(ExtractionResult result) {
        var affected = result.machines().stream()
                .filter(m -> !m.statesWithUnreadDeclaration().isEmpty())
                .toList();
        if (affected.isEmpty()) return;
        System.out.println();
        for (StateMachine m : affected) {
            System.out.printf("  ! %s: state(s) %s enumerated from the permits clause but never "
                            + "read; %d edge(s) touch them%n",
                    m.name(), m.statesWithUnreadDeclaration(), m.transitionsViaUnreadDeclaration());
        }
        System.out.println("    Those edges were matched through a qualified name Spoon guessed "
                + "for a declaration");
        System.out.println("    that was not in --src. Re-run with the whole hierarchy before "
                + "quoting these counts.");
    }

    /** The successor spellings a machine's edges used, compactly. */
    private static String formList(StateMachine m) {
        if (m.successorForms().isEmpty()) return "-";
        return m.successorForms().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** How the machine's dispatch installed its successors, compactly. */
    private static String commitList(StateMachine m) {
        if (m.commitForms().isEmpty()) return "-";
        return m.commitForms().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /**
     * Totals along both axes. State enumeration is exact everywhere, but transition
     * recovery is not, and its accuracy varies independently with where dispatch
     * lives and with how the successor is written — so the resolved/total figures
     * are broken out per dispatch position AND per successor form rather than
     * pooled. A recall gap can then be attributed to the thing that caused it.
     */
    private static void printEncodingRollup(ExtractionResult result) {
        if (result.machines().size() < 2) return;
        Map<StateMachine.Encoding, int[]> byDispatch = new EnumMap<>(StateMachine.Encoding.class);
        Map<SuccessorForm, Integer> byForm = new EnumMap<>(SuccessorForm.class);
        for (StateMachine m : result.machines()) {
            int[] acc = byDispatch.computeIfAbsent(m.encoding(), k -> new int[3]);
            acc[0]++;                                             // machines
            acc[1] += (int) m.resolvedTransitionCount();          // resolved edges
            acc[2] += m.transitions().size();                     // total edges
            for (Transition t : m.transitions()) {
                if (t.form() != null) byForm.merge(t.form(), 1, Integer::sum);
            }
        }
        System.out.println("-".repeat(112));
        System.out.printf("%-22s %-20s %6s %6s %9s%n",
                "BY DISPATCH", "", "MACHINES", "TRANS", "RESOLVED");
        byDispatch.forEach((enc, acc) ->
                System.out.printf("%-22s %-20s %6d %6d %9s%n",
                        "", enc, acc[0], acc[2], acc[1] + "/" + acc[2]));
        if (!byForm.isEmpty()) {
            System.out.printf("%-22s %s%n", "BY SUCCESSOR FORM",
                    byForm.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("  ")));
        }
    }

    private static String require(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println("error: " + flag + " requires a value");
            System.exit(2);
        }
        return args[i];
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static void printUsage() {
        System.out.println("""
            SealFSM — extract finite state machines from sealed Java hierarchies

            Usage:
              java -jar sealfsm.jar --src <path> [--src <path> ...] [options]

            Options:
              --src <path>      Source file or directory to analyse (repeatable, required)
              --out <dir>       Output directory (default: ./out)
              --format <fmt>    dot | scxml | both   (default: both)
              --classpath <cp>  Classpath for types not in --src, separated by
                                the platform path separator (repeatable). Fewer
                                unresolved references means fewer
                                under-reported edges; analysis stays
                                noClasspath either way.
              --quiet           Suppress the diagnostics listing
              --explain         For every REJECTED sealed root, print each
                                classifier predicate and why it failed
              -h, --help        Show this help
            """);
    }
}
