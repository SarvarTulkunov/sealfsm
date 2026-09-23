import io.sealfsm.Analyzer;
import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The inter-procedural depth sweep (FIXLOG F29 §"Depth sweep"): run the analyzer
 * over every fixture directory given, one directory per model exactly as
 * scripts/capture-golden.sh does, at the depth budget this JVM was started with
 * ({@code -Dsealfsm.maxInterprocDepth=k}), and print one row per axis cell.
 *
 * <p>The budget is read once, at class initialisation of TransitionExtractor, so
 * each k is its own JVM — which is also what keeps one run's caches out of the
 * next run's timing. Analysis time is measured in-process around
 * {@code Analyzer.analyze} alone: model building is identical at every k, and JVM
 * start-up would otherwise swamp the difference being measured.
 *
 * <p>An axis cell is (Encoding × CommitForm set) as the summary table prints them;
 * a machine committing through two forms is its own row rather than being counted
 * twice.
 *
 * Usage: java -Dsealfsm.maxInterprocDepth=k -cp "target/sealfsm.jar:out" DepthSweep dir...
 */
public class DepthSweep {

    public static void main(String[] args) {
        int k = TransitionExtractor.maxInterproceduralDepth();
        Map<String, int[]> cells = new TreeMap<>();   // cell -> {machines, transitions, resolved}
        long analysisNanos = 0;
        int machines = 0;
        for (String dir : expand(args)) {
            Launcher launcher = new Launcher();
            launcher.addInputResource(dir);
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setCommentEnabled(false);
            launcher.buildModel();
            CtModel model = launcher.getModel();
            long t0 = System.nanoTime();
            ExtractionResult r = new Analyzer().analyze(model);
            analysisNanos += System.nanoTime() - t0;
            for (StateMachine m : r.machines()) {
                machines++;
                String commit = m.commitForms().stream().map(CommitForm::name).sorted()
                        .collect(Collectors.joining("+"));
                String cell = m.encoding() + " / " + (commit.isEmpty() ? "-" : commit);
                int[] c = cells.computeIfAbsent(cell, x -> new int[3]);
                c[0]++;
                c[1] += m.transitions().size();
                c[2] += m.resolvedTransitionCount();
            }
        }
        int trans = 0, resolved = 0;
        for (Map.Entry<String, int[]> e : cells.entrySet()) {
            int[] c = e.getValue();
            trans += c[1];
            resolved += c[2];
            System.out.printf("k=%d\t%s\t%d\t%d\t%d\t%d%n", k, e.getKey(), c[0], c[1], c[2], c[1] - c[2]);
        }
        System.out.printf("k=%d\tTOTAL\t%d\t%d\t%d\t%d\tanalysis_ms=%d%n",
                k, machines, trans, resolved, trans - resolved, analysisNanos / 1_000_000);
    }

    /** Each argument that is a directory of fixture directories is expanded, sorted. */
    private static List<String> expand(String[] args) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            File f = new File(a);
            File[] kids = f.listFiles(File::isDirectory);
            if (a.endsWith("/*") || a.endsWith("\\*")) {
                File parent = new File(a.substring(0, a.length() - 2));
                File[] ks = parent.listFiles(File::isDirectory);
                if (ks != null) {
                    Arrays.sort(ks);
                    for (File x : ks) out.add(x.getPath());
                }
            } else if (kids != null || f.isFile()) {
                out.add(a);
            }
        }
        return out;
    }
}
