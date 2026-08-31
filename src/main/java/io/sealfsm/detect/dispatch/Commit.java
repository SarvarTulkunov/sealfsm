package io.sealfsm.detect.dispatch;

import io.sealfsm.model.CommitForm;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtElement;

/**
 * How one chosen successor is <em>installed</em> — the second of the two axes
 * this package separates, and the one that carries the precision load.
 *
 * <p>A transition switch and an exhaustive fold are structurally
 * indistinguishable <em>at the discrimination</em>: {@code switch (state)} looks
 * the same whether its arms yield the next state or a log string. Only the
 * codomain separates them, which is why the commit is a requirement rather than
 * a label — see {@link CommitClassifier}.
 *
 * @param form which mechanism installs the successor
 * @param value the expression that IS the successor, or {@code null} when the
 *        commit was recognised without one in hand (a chain commits inside each
 *        branch, so the classifier can answer "this discrimination commits by
 *        field mutation" before any particular branch is walked)
 * @param at the syntactic node that performs the installation — the
 *        {@code CtReturn}, the {@code CtAssignment}, the {@code CtLocalVariable},
 *        the carrier call. Kept so a diagnostic can point at the commit rather
 *        than at the whole dispatch.
 */
public record Commit(CommitForm form, CtExpression<?> value, CtElement at) {

    /** A commit recognised by form alone, with no particular value in hand yet. */
    public static Commit of(CommitForm form, CtElement at) {
        return new Commit(form, null, at);
    }
}
