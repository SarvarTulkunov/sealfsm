# findings.md — 2026-08-23

One entry per distinct defect. Scope: split == dev, protocol-conformant examples only
(n=1: lcp_automation_chatgpt). Ordered per CORPUS_PROTOCOL.md section 12:
fabricated-edge and wrong-state-set first.

---

finding-id:      centralized-carrier-nondetection
symptom:         SealFSM reports zero state machines for a sealed hierarchy whose
                 centralized dispatch switch commits its result as a constructor
                 argument to a non-hierarchy carrier record, rather than as a direct
                 return/field/local value of the hierarchy type.
F-code:          NEW
examples:        [lcp_automation_chatgpt]
idiom:           CENTRALIZED_DISPATCH
commit_shape:    argument-passed
expected:        10 states (Initial, Starting, Closed, Stopped, Closing, Stopping,
                 ReqSent, AckRcvd, AckSent, Opened) and 120 transitions, e.g.
                 "Initial  Up  Closed" and "Closed  RCR+  Closed" (oracle/transitions.tsv).
actual:          0 machines emitted, 0 states, 0 transitions. Sole diagnostic:
                 "[INFO] lcpchatgpt.LcpState: skipped - no transition producer found
                 (may be event/? type or unresolved dispatch)". Exit code 1
                 ("no machines found" path, not a crash). No .dot, no .scxml written.
node-shape:      A sealed interface H with ten permitted record subtypes. A method M,
                 declared on a class OUTSIDE H, contains a CtSwitchExpression whose
                 selector's static type IS H (dispatchesOnHierarchy would accept it),
                 but whose immediate syntactic parent is a CtReturn where the ENCLOSING
                 METHOD's return type is a carrier record type C, C != H, one of whose
                 record components is of type H. Each arm of that switch delegates
                 (a plain CtInvocation, not inlined) to a second per-state helper
                 method that itself contains an inner CtSwitchExpression selecting on
                 an unrelated enum type (the event alphabet), whose arms construct C
                 via `new C(new ConcreteH(), otherArgs)` — an H-typed CtConstructorCall
                 passed as a direct constructor argument to C, which is itself the
                 yielded/returned value of the inner switch. No switch anywhere in the
                 model has BOTH a selector typed H AND a commit (return/field-write/
                 local-declaration) typed H directly, so DispatchCommitDetector's
                 commitFormOf(...) returns null for the outer switch ("foreign
                 codomain — an exhaustive fold") and never inspects the inner ones
                 (their selector type is the event enum, not H, so
                 dispatchesOnHierarchy already rejects them). CarrierTransitionDetector
                 does not apply either: its POLY_CARRIER recognition requires the
                 carrier-returning method to be declared ON a permitted subtype
                 (POLYMORPHIC dispatch); here the carrier-returning methods are all
                 declared outside H (CENTRALIZED_DISPATCH). The commit-form axis
                 (VALUE_RETURN / FIELD_MUTATION / LOCAL_ACCUMULATOR / POLY_CARRIER)
                 therefore has no member for "H committed one constructor-argument
                 level below a centralized dispatch's own codomain match point" — a
                 fifth combination of {encoding} x {commit} outside the four the
                 commit-form fixture family currently covers.
soundness-class: wrong-state-set
diagnostic:      emitted
