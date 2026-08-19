package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
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
 *        [--out out-dir] [--format dot|scxml|both] [--quiet]
 * </pre>
 */
public final class Main {

    private enum Format { DOT, SCXML, BOTH }

    public static void main(String[] args) {
        List<String> sources = new ArrayList<>();
        Path out = Path.of("out");
        Format format = Format.BOTH;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--src" -> sources.add(require(args, ++i, "--src"));
                case "--out" -> out = Path.of(require(args, ++i, "--out"));
                case "--format" -> format = Format.valueOf(require(args, ++i, "--format").toUpperCase());
                case "--quiet" -> quiet = true;
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
        launcher.getEnvironment().setNoClasspath(true);   // analyse source without full classpath
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        ExtractionResult result = new Analyzer().analyze(model);

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
        printEncodingRollup(result);
        if (!quiet && !result.diagnostics().isEmpty()) {
            System.out.println("-".repeat(72));
            System.out.println("Diagnostics:");
            result.diagnostics().forEach(d -> System.out.println("  " + d));
        }
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
              --quiet           Suppress the diagnostics listing
              -h, --help        Show this help
            """);
    }
}
