import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Level-2 census: how is a STATE FIELD written, and is it ever discriminated?
 *
 * A state field is a field F of reference type whose value is assigned in >= 2
 * distinct places in its own class. For each one this asks three things:
 *
 *   discriminated?   does the class switch on F, or test it with == / instanceof?
 *   written how?     directly inside a switch over F's own type (STATE-MAJOR),
 *                    directly inside a switch over another type (EVENT-MAJOR),
 *                    or inside a method CALLED from >= 2 arms of such a switch
 *                    (EVENT-MAJOR, one hop -- the k = 1 probe shape),
 *                    or from no dispatch at all.
 *
 * Parse-only, one class at a time; the call graph is intra-class and one hop
 * deep, which is exactly the depth the tool's own probe uses.
 */
public final class Census2 {

    record Field(String file, String cls, String name, String type,
                 boolean discriminated, boolean stateMajor,
                 boolean eventMajorDirect, boolean eventMajorViaCallee,
                 int writes) {}

    static final List<Field> FIELDS = new ArrayList<>();
    static final Map<String, String> KIND = new HashMap<>();
    static int filesParsed = 0;

    static final Set<String> NOISE = Set.of(
        "String", "Object", "int", "long", "boolean", "char", "byte", "short",
        "float", "double", "Integer", "Long", "Boolean", "Character", "Byte",
        "Short", "Float", "Double", "Void", "CharSequence", "StringBuilder",
        "Class", "Number", "BigDecimal", "BigInteger", "var", "List", "Map",
        "Set", "ArrayList", "HashMap", "Thread", "Exception", "Throwable");

    public static void main(String[] args) throws Exception {
        List<File> files = new ArrayList<>();
        for (String a : args) {
            try (var s = Files.walk(Path.of(a))) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
            }
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
        int batch = 300;
        for (int i = 0; i < files.size(); i += batch) {
            List<File> chunk = files.subList(i, Math.min(i + batch, files.size()));
            try {
                JavacTask task = (JavacTask) jc.getTask(new PrintWriter(Writer.nullWriter()),
                        fm, d -> { }, List.of("-proc:none"), null,
                        fm.getJavaFileObjectsFromFiles(chunk));
                for (CompilationUnitTree cu : task.parse()) {
                    filesParsed++;
                    new ClassWalk(cu).scan(cu, null);
                }
            } catch (Throwable t) { /* unparseable chunk skipped */ }
        }
        report();
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

    static ExpressionTree parens(ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        return e;
    }

    static String nameOf(ExpressionTree e) {
        if (e == null) return null;
        e = parens(e);
        if (e instanceof IdentifierTree i) return i.getName().toString();
        if (e instanceof MemberSelectTree m && m.getExpression().toString().equals("this"))
            return m.getIdentifier().toString();
        return null;
    }

    /** Walks each class declaration and analyses its fields. */
    static final class ClassWalk extends TreeScanner<Void, Void> {
        final CompilationUnitTree cu;
        ClassWalk(CompilationUnitTree cu) { this.cu = cu; }

        @Override public Void visitClass(ClassTree n, Void p) {
            boolean sealed = n.getModifiers().getFlags().stream().anyMatch(f -> f.name().equals("SEALED"));
            KIND.merge(n.getSimpleName().toString(), switch (n.getKind()) {
                case ENUM -> "enum";
                case INTERFACE -> sealed ? "SEALED interface" : "interface";
                case RECORD -> sealed ? "SEALED record" : "record";
                default -> sealed ? "SEALED class" : "class";
            }, (a, b) -> a);
            analyse(n);
            return super.visitClass(n, p);
        }

        void analyse(ClassTree c) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (Tree m : c.getMembers()) {
                if (m instanceof VariableTree v) {
                    String t = simple(v.getType());
                    if (t != null && !NOISE.contains(t)) fields.put(v.getName().toString(), t);
                }
            }
            if (fields.isEmpty()) return;

            Map<String, Integer> writes = new HashMap<>();
            Set<String> discriminated = new HashSet<>();
            Set<String> stateMajor = new HashSet<>();
            Set<String> eventDirect = new HashSet<>();
            Map<String, Set<String>> writtenByMethod = new HashMap<>();  // method -> fields it writes
            // switch over T -> methods invoked in >= 2 arms, plus T
            List<Map.Entry<String, Set<String>>> eventSwitchCalls = new ArrayList<>();

            for (Tree m : c.getMembers()) {
                if (!(m instanceof MethodTree mt) || mt.getBody() == null) continue;
                Map<String, String> scope = new LinkedHashMap<>(fields);
                for (VariableTree v : mt.getParameters()) scope.put(v.getName().toString(), simple(v.getType()));
                String mName = mt.getName().toString();

                new TreeScanner<Void, String>() {   // param = enclosing switch selector type
                    @Override public Void visitVariable(VariableTree v, String sel) {
                        scope.put(v.getName().toString(), simple(v.getType()));
                        return super.visitVariable(v, sel);
                    }

                    @Override public Void visitAssignment(AssignmentTree a, String sel) {
                        String n = nameOf(a.getVariable());
                        if (n != null && fields.containsKey(n)) {
                            writes.merge(n, 1, Integer::sum);
                            writtenByMethod.computeIfAbsent(mName, k -> new HashSet<>()).add(n);
                            if (sel != null) {
                                if (sel.equals(fields.get(n))) stateMajor.add(n);
                                else eventDirect.add(n);
                            }
                        }
                        return super.visitAssignment(a, sel);
                    }

                    @Override public Void visitSwitch(SwitchTree s, String sel) {
                        String t = selType(s.getExpression());
                        collectArmCalls(s.getExpression(), s.getCases(), t);
                        if (t != null && fields.containsValue(t)) markDiscriminated(s.getExpression());
                        return super.visitSwitch(s, t == null ? sel : t);
                    }

                    @Override public Void visitSwitchExpression(SwitchExpressionTree s, String sel) {
                        String t = selType(s.getExpression());
                        collectArmCalls(s.getExpression(), s.getCases(), t);
                        if (t != null && fields.containsValue(t)) markDiscriminated(s.getExpression());
                        return super.visitSwitchExpression(s, t == null ? sel : t);
                    }

                    @Override public Void visitBinary(BinaryTree b, String sel) {
                        markDiscriminated(b.getLeftOperand());
                        markDiscriminated(b.getRightOperand());
                        return super.visitBinary(b, sel);
                    }

                    @Override public Void visitInstanceOf(InstanceOfTree i, String sel) {
                        markDiscriminated(i.getExpression());
                        return super.visitInstanceOf(i, sel);
                    }

                    String selType(ExpressionTree e) {
                        String n = nameOf(e);
                        return n == null ? null : scope.get(n);
                    }

                    void markDiscriminated(ExpressionTree e) {
                        String n = nameOf(e);
                        if (n != null && fields.containsKey(n)) discriminated.add(n);
                    }

                    void collectArmCalls(ExpressionTree selector, List<? extends CaseTree> cases, String t) {
                        if (t == null) return;
                        Set<String> called = new LinkedHashSet<>();
                        for (CaseTree ct : cases) {
                            new TreeScanner<Void, Void>() {
                                @Override public Void visitMethodInvocation(MethodInvocationTree mi, Void p) {
                                    ExpressionTree sel = mi.getMethodSelect();
                                    String nm = sel instanceof IdentifierTree id ? id.getName().toString()
                                            : sel instanceof MemberSelectTree ms
                                              && ms.getExpression().toString().equals("this")
                                                ? ms.getIdentifier().toString() : null;
                                    if (nm != null) called.add(nm);
                                    return super.visitMethodInvocation(mi, p);
                                }
                            }.scan(ct, null);
                        }
                        if (!called.isEmpty()) eventSwitchCalls.add(Map.entry(t, called));
                    }
                }.scan(mt.getBody(), null);
            }

            for (var e : fields.entrySet()) {
                String name = e.getKey(), type = e.getValue();
                int w = writes.getOrDefault(name, 0);
                if (w < 2) continue;                       // not a state field: assigned at most once
                boolean viaCallee = false;
                for (var sw : eventSwitchCalls) {
                    if (sw.getKey().equals(type)) continue;     // that is the state-major case
                    for (String called : sw.getValue()) {
                        if (writtenByMethod.getOrDefault(called, Set.of()).contains(name)) {
                            viaCallee = true;
                            break;
                        }
                    }
                    if (viaCallee) break;
                }
                FIELDS.add(new Field(cu.getSourceFile().getName(), c.getSimpleName().toString(),
                        name, type, discriminated.contains(name), stateMajor.contains(name),
                        eventDirect.contains(name), viaCallee, w));
            }
        }
    }

    static String base(String f) {
        int i = Math.max(f.lastIndexOf('\\'), f.lastIndexOf('/'));
        return i < 0 ? f : f.substring(i + 1);
    }

    static void report() {
        System.out.println("files parsed                       : " + filesParsed);
        System.out.println("state fields (reference type, >=2 writes): " + FIELDS.size());
        System.out.println();

        var closed = FIELDS.stream()
                .filter(f -> { String k = KIND.getOrDefault(f.type(), "");
                               return k.equals("enum") || k.startsWith("SEALED"); })
                .toList();
        System.out.println("of which the state type is a CLOSED set (enum or sealed): " + closed.size());
        long disc = closed.stream().filter(Field::discriminated).count();
        System.out.printf("  discriminated somewhere in its class : %d%n", disc);
        System.out.printf("  never discriminated                  : %d%n", closed.size() - disc);
        System.out.println();
        System.out.printf("  written by a STATE-major dispatch    : %d%n",
                closed.stream().filter(Field::stateMajor).count());
        System.out.printf("  written by an EVENT-major dispatch   : %d%n",
                closed.stream().filter(Field::eventMajorDirect).count());
        System.out.printf("  written one hop from an EVENT switch : %d%n",
                closed.stream().filter(Field::eventMajorViaCallee).count());
        System.out.printf("  written by NO dispatch at all        : %d%n",
                closed.stream().filter(f -> !f.stateMajor() && !f.eventMajorDirect()
                        && !f.eventMajorViaCallee()).count());
        System.out.println();

        System.out.println("--- EVENT-major (direct or one hop), closed state type ---");
        closed.stream().filter(f -> f.eventMajorDirect() || f.eventMajorViaCallee())
              .limit(60).forEach(f ->
                System.out.printf("  %-34s %-30s %-18s : %-22s [%s] writes=%d disc=%s %s%n",
                        base(f.file()), f.cls(), f.name(), f.type(),
                        KIND.getOrDefault(f.type(), "?"), f.writes(), f.discriminated(),
                        f.eventMajorViaCallee() ? "ONE-HOP" : "direct"));
        System.out.println();
        System.out.println("--- STATE-major, closed state type (the control) ---");
        closed.stream().filter(Field::stateMajor).limit(30).forEach(f ->
                System.out.printf("  %-34s %-30s %-18s : %-22s [%s] writes=%d%n",
                        base(f.file()), f.cls(), f.name(), f.type(),
                        KIND.getOrDefault(f.type(), "?"), f.writes()));
    }
}
