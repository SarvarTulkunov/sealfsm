package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.serialize.DotSerializer;
import io.sealfsm.serialize.ScxmlSerializer;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        System.out.println("-".repeat(72));
        System.out.printf("%-28s %-12s %7s %7s %10s%n",
                "MACHINE", "ENCODING", "STATES", "TRANS", "RESOLVED");
        for (StateMachine m : result.machines()) {
            System.out.printf("%-28s %-12s %7d %7d %9s%n",
                    truncate(m.name(), 28),
                    m.encoding(),
                    m.allStates().size(),
                    m.transitions().size(),
                    m.resolvedTransitionCount() + "/" + m.transitions().size());
        }
        if (!quiet && !result.diagnostics().isEmpty()) {
            System.out.println("-".repeat(72));
            System.out.println("Diagnostics:");
            result.diagnostics().forEach(d -> System.out.println("  " + d));
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
