import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * A syntactic census of TRANSITION-TABLE ORIENTATION in real Java.
 *
 * For every switch committing >= 2 syntactically distinct values to ONE target
 * of reference type R (a field, a local, or the method's return), classify:
 *
 *   STATE-MAJOR : the selector's declared type is R itself  (switch over the state)
 *   EVENT-MAJOR : the selector's declared type is not R     (switch over the input)
 *
 * Parse-only, no resolution. Types are read as written; a per-scope
 * name -> declared-type map resolves the selector and the assignment targets.
 * That is deliberately conservative: a selector whose type is not visible in
 * the local scope is skipped rather than guessed.
 */
public final class Census {

    record Site(String file, String cls, String method, String selType,
                String target, String rType, boolean eventMajor,
                boolean helperTakesState, boolean rIsField) {}

    static final List<Site> SITES = new ArrayList<>();
    static final Map<String, String> KIND = new HashMap<>();   // simple name -> declared kind
    static int filesParsed = 0;
    static int switchesSeen = 0;
    static int selectorUnknown = 0;

    static final Set<String> NOISE = Set.of(
        "String", "Object", "int", "long", "boolean", "char", "byte", "short",
        "float", "double", "Integer", "Long", "Boolean", "Character", "Byte",
        "Short", "Float", "Double", "Void", "CharSequence", "StringBuilder",
        "Class", "Number", "BigDecimal", "BigInteger", "var");

    public static void main(String[] args) throws Exception {
        List<File> files = new ArrayList<>();
        for (String a : args) {
            try (var s = Files.walk(Path.of(a))) {
                s.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> files.add(p.toFile()));
            }
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
        int batch = 300;
        for (int i = 0; i < files.size(); i += batch) {
            List<File> chunk = files.subList(i, Math.min(i + batch, files.size()));
            try {
                JavacTask task = (JavacTask) jc.getTask(
                        new PrintWriter(Writer.nullWriter()), fm, d -> { },
                        List.of("-proc:none"), null,
                        fm.getJavaFileObjectsFromFiles(chunk));
                for (CompilationUnitTree cu : task.parse()) {
                    filesParsed++;
                    new Scan(cu).scan(cu, null);
                }
            } catch (Throwable t) {
                // a chunk javac cannot parse at all is skipped; counted by omission
            }
        }
        report();
    }

    static final class Scan extends TreeScanner<Void, Void> {
        final CompilationUnitTree cu;
        final Deque<Map<String, String>> scopes = new ArrayDeque<>();
        String cls = "?";
        String method = "?";
        String returnType = null;
        Set<String> fieldTypes = new LinkedHashSet<>();

        Scan(CompilationUnitTree cu) {
            this.cu = cu;
            scopes.push(new LinkedHashMap<>());
        }

        String typeOf(String name) {
            for (Map<String, String> m : scopes) {
                String t = m.get(name);
                if (t != null) return t;
            }
            return null;
        }

        static String simple(Tree t) {
            if (t == null) return null;
            String s = t.toString();
            int lt = s.indexOf('<');
            if (lt > 0) s = s.substring(0, lt);
            s = s.replace("[]", "").trim();
            int dot = s.lastIndexOf('.');
            if (dot > 0) s = s.substring(dot + 1);
            return s.trim();
        }

        static String kindOf(ClassTree n) {
            boolean sealed = n.getModifiers().getFlags().stream()
                    .anyMatch(f -> f.name().equals("SEALED"));
            return switch (n.getKind()) {
                case ENUM -> "enum";
                case INTERFACE -> sealed ? "SEALED interface" : "interface";
                case RECORD -> sealed ? "SEALED record" : "record";
                default -> sealed ? "SEALED class" : "class";
            };
        }

        static ExpressionTree stripParens(ExpressionTree e) {
            while (e instanceof ParenthesizedTree p) e = p.getExpression();
            return e;
        }

        /** the variable an expression names, if it names one */
        static String nameOf(ExpressionTree e) {
            if (e == null) return null;
            e = stripParens(e);
            if (e instanceof IdentifierTree i) return i.getName().toString();
            if (e instanceof MemberSelectTree m
                    && m.getExpression().toString().equals("this")) {
                return m.getIdentifier().toString();
            }
            return null;
        }

        @Override
        public Void visitClass(ClassTree n, Void p) {
            String prevCls = cls;
            Set<String> prevFields = fieldTypes;
            cls = n.getSimpleName().toString();
            KIND.merge(cls, kindOf(n), (a, b) -> a);
            scopes.push(new LinkedHashMap<>());
            fieldTypes = new LinkedHashSet<>();
            for (Tree m : n.getMembers()) {
                if (m instanceof VariableTree v) {
                    scopes.peek().put(v.getName().toString(), simple(v.getType()));
                    fieldTypes.add(simple(v.getType()));
                }
            }
            super.visitClass(n, p);
            scopes.pop();
            cls = prevCls;
            fieldTypes = prevFields;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree n, Void p) {
            String prevM = method;
            String prevR = returnType;
            method = n.getName().toString();
            returnType = n.getReturnType() == null ? null : simple(n.getReturnType());
            scopes.push(new LinkedHashMap<>());
            for (VariableTree v : n.getParameters()) {
                scopes.peek().put(v.getName().toString(), simple(v.getType()));
            }
            super.visitMethod(n, p);
            scopes.pop();
            method = prevM;
            returnType = prevR;
            return null;
        }

        @Override
        public Void visitVariable(VariableTree n, Void p) {
            scopes.peek().put(n.getName().toString(), simple(n.getType()));
            return super.visitVariable(n, p);
        }

        @Override
        public Void visitSwitch(SwitchTree n, Void p) {
            handle(n.getExpression(), n.getCases());
            return super.visitSwitch(n, p);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree n, Void p) {
            handle(n.getExpression(), n.getCases());
            return super.visitSwitchExpression(n, p);
        }

        void handle(ExpressionTree selector, List<? extends CaseTree> cases) {
            switchesSeen++;
            String selName = nameOf(selector);
            String selType = selName == null ? null : typeOf(selName);
            if (selType == null) {
                selectorUnknown++;
                return;
            }

            Map<String, Set<String>> commits = new LinkedHashMap<>();
            Set<String> helper = new LinkedHashSet<>();
            for (CaseTree c : cases) collect(c, commits, helper);

            for (var e : commits.entrySet()) {
                if (e.getValue().size() < 2) continue;   // one arm is a factory, not a table
                String target = e.getKey();
                String rType = target.equals("<return>") ? returnType : typeOf(target);
                if (rType == null || NOISE.contains(rType)) continue;
                SITES.add(new Site(cu.getSourceFile().getName(), cls, method, selType,
                        target, rType, !rType.equals(selType),
                        helper.contains(target), fieldTypes.contains(rType)));
            }
        }

        void collect(Tree arm, Map<String, Set<String>> commits, Set<String> helper) {
            new TreeScanner<Void, Void>() {
                @Override public Void visitAssignment(AssignmentTree a, Void p) {
                    String n = nameOf(a.getVariable());
                    if (n != null) record(n, a.getExpression());
                    return super.visitAssignment(a, p);
                }

                @Override public Void visitReturn(ReturnTree r, Void p) {
                    if (r.getExpression() != null && returnType != null
                            && !returnType.equals("void")) {
                        record("<return>", r.getExpression());
                    }
                    return super.visitReturn(r, p);
                }

                @Override public Void visitVariable(VariableTree v, Void p) {
                    scopes.peek().put(v.getName().toString(), simple(v.getType()));
                    if (v.getInitializer() != null) {
                        record(v.getName().toString(), v.getInitializer());
                    }
                    return super.visitVariable(v, p);
                }

                // a nested switch is its own site; a nested class or lambda its own scope
                @Override public Void visitSwitch(SwitchTree s, Void p) { return null; }
                @Override public Void visitSwitchExpression(SwitchExpressionTree s, Void p) { return null; }
                @Override public Void visitClass(ClassTree c, Void p) { return null; }
                @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void p) { return null; }

                void record(String target, ExpressionTree value) {
                    commits.computeIfAbsent(target, k -> new LinkedHashSet<>())
                           .add(value.toString());
                    if (stripParens(value) instanceof MethodInvocationTree mi) {
                        String rt = target.equals("<return>") ? returnType : typeOf(target);
                        for (ExpressionTree arg : mi.getArguments()) {
                            String an = nameOf(arg);
                            String at = an == null ? null : typeOf(an);
                            if (at != null && rt != null && at.equals(rt)) helper.add(target);
                        }
                    }
                }
            }.scan(arm, null);
        }
    }

    static String base(String f) { int i = Math.max(f.lastIndexOf(92), f.lastIndexOf(47)); return i < 0 ? f : f.substring(i + 1); }

    static void report() {
        System.out.println("files parsed              : " + filesParsed);
        System.out.println("switches seen             : " + switchesSeen);
        System.out.println("  selector type not local : " + selectorUnknown + "  (skipped, never guessed)");
        System.out.println("commit tables (>=2 arms)  : " + SITES.size());
        long ev = SITES.stream().filter(Site::eventMajor).count();
        System.out.printf("  state-major             : %d%n", SITES.size() - ev);
        System.out.printf("  EVENT-major             : %d%n", ev);
        System.out.println();

        var stateful = SITES.stream().filter(Site::rIsField).toList();
        long evs = stateful.stream().filter(Site::eventMajor).count();
        System.out.println("committed type is ALSO a field of the class (i.e. persisted state): " + stateful.size());
        System.out.printf("  state-major             : %d%n", stateful.size() - evs);
        System.out.printf("  EVENT-major             : %d%n", evs);
        System.out.println();

        var reported = SITES.stream()
                .filter(s -> s.eventMajor() && s.helperTakesState()).toList();
        System.out.println("EVENT-major whose arm calls a helper TAKING the current state");
        System.out.println("  (the exact reported shape, next = extract(current, event)) : " + reported.size());
        System.out.println();

        // Is the state discriminated ANYWHERE in the same class? If not, the
        // extractor has no from-state to attribute the event-major arms to.
        Set<String> stateMajorInClass = new HashSet<>();
        for (Site s : SITES) if (!s.eventMajor()) stateMajorInClass.add(s.cls() + "#" + s.rType());
        var orphan = SITES.stream()
                .filter(s -> s.eventMajor() && !stateMajorInClass.contains(s.cls() + "#" + s.rType()))
                .toList();
        System.out.println("EVENT-major sites whose class holds NO state-major switch on the same type");
        System.out.println("  (no from-state available anywhere in the class)          : " + orphan.size());
        System.out.println();

        Map<String, Long> byKind = new TreeMap<>();
        for (Site s : SITES) {
            if (!s.eventMajor()) continue;
            byKind.merge(KIND.getOrDefault(s.rType(), "(not declared in corpus)"), 1L, Long::sum);
        }
        System.out.println("declared kind of the committed type R, over EVENT-major sites:");
        byKind.forEach((k, v) -> System.out.printf("  %-26s %d%n", k, v));
        System.out.println();

        // The FSM-specific narrowing: the committed target is a FIELD (so the value
        // persists past the call) and its declared type is an enum or a sealed
        // hierarchy (so the value ranges over a closed, named set). A parser
        // building a Node tree or a factory returning a MethodHandle is excluded.
        var fsm = SITES.stream()
                .filter(Site::rIsField)
                .filter(s -> {
                    String k = KIND.getOrDefault(s.rType(), "");
                    return k.equals("enum") || k.startsWith("SEALED");
                })
                .toList();
        long fsmEv = fsm.stream().filter(Site::eventMajor).count();
        System.out.println("=== FSM-shaped: a FIELD of enum/sealed type written by >=2 arms ===");
        System.out.println("  total                   : " + fsm.size());
        System.out.printf("  state-major             : %d%n", fsm.size() - fsmEv);
        System.out.printf("  EVENT-major             : %d%n", fsmEv);
        System.out.println();
        System.out.println("--- every FSM-shaped EVENT-major site ---");
        fsm.stream().filter(Site::eventMajor).forEach(s ->
                System.out.printf("  %-32s %-28s %-26s switch(%-22s) -> %s : %s [%s]%s%n",
                        base(s.file()), s.cls(), s.method(), s.selType(), s.target(),
                        s.rType(), KIND.getOrDefault(s.rType(), "?"),
                        s.helperTakesState() ? "  [helper(state,..)]" : ""));
        System.out.println();
        System.out.println("--- every FSM-shaped STATE-major site (the control) ---");
        fsm.stream().filter(s -> !s.eventMajor()).forEach(s ->
                System.out.printf("  %-32s %-28s %-26s switch(%-22s) -> %s : %s [%s]%n",
                        base(s.file()), s.cls(), s.method(), s.selType(), s.target(),
                        s.rType(), KIND.getOrDefault(s.rType(), "?")));
        System.out.println();

        System.out.println("--- EVENT-major sites whose committed type is a field (sample) ---");
        stateful.stream().filter(Site::eventMajor).limit(30).forEach(s ->
                System.out.printf("  %-30s %-26s %-24s switch(%s) -> %s : %s%s%n",
                        base(s.file()), s.cls(), s.method(), s.selType(), s.target(), s.rType(),
                        s.helperTakesState() ? "   [helper(state,..)]" : ""));
        System.out.println();
        System.out.println("--- EVENT-major with helper(current, ...) (sample) ---");
        reported.stream().limit(25).forEach(s ->
                System.out.printf("  %-30s %-26s %-24s switch(%s) -> %s : %s%n",
                        base(s.file()), s.cls(), s.method(), s.selType(), s.target(), s.rType()));
    }
}
