package com.kk.pde.ds.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
// Mockito's entry points are static methods too, and are imported the same way as
// JUnit's assertions. `mock`, `doThrow`, `verify` etc. read like a small language.
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.ResultLog;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kk.pde.ds.api.IGreet;

/**
 * <h2>Testing code that depends on other code: test doubles and Mockito</h2>
 *
 * <p>
 * Every other test in this project exercises an object that stands alone —
 * {@link Greet}, {@code Json}, {@code DockLayout}. Most production code is not like
 * that. {@link GreetHealthCheck} is the first subject with a <strong>collaborator</strong>:
 * at run time OSGi injects an {@link IGreet} into its private {@code greetService} field,
 * and {@code execute()} calls it. To test the health check we must decide what plays the
 * part of {@code IGreet}.
 * </p>
 *
 * <h3>Why not just use the real {@link Greet}?</h3>
 * <p>
 * We can, and the first test below does. But look at
 * {@code GreetHealthCheck.execute()}: it has a {@code catch} branch that reports
 * {@code CRITICAL} when the service throws. The real {@code Greet.greet()} never throws.
 * With the real object that branch is <em>unreachable</em> — we cannot test it, so we
 * cannot know it works. That is the honest, everyday reason for test doubles: to put
 * the code under test into situations its real collaborators cannot produce
 * (failures, timeouts, empty results, absurd values).
 * </p>
 *
 * <h3>Vocabulary — the five kinds of test double</h3>
 * <p>
 * "Test double" is the umbrella term (as in "stunt double"). The names below are used
 * loosely in practice, but the distinctions are worth knowing:
 * </p>
 * <ul>
 * <li><strong>Dummy</strong> — passed around but never used; exists only to fill a
 * parameter list.</li>
 * <li><strong>Stub</strong> — returns canned answers. "When asked X, say Y."</li>
 * <li><strong>Mock</strong> — a stub that also <em>records</em> how it was called, so
 * the test can ask "was {@code greet()} invoked exactly once?".</li>
 * <li><strong>Spy</strong> — a real object with recording wrapped around it.</li>
 * <li><strong>Fake</strong> — a working but simplified implementation (an in-memory
 * database in place of a real one).</li>
 * </ul>
 * <p>
 * Mockito's {@code mock()} produces something that can act as any of the first three,
 * which is why the word "mock" is used for all of them in daily speech.
 * </p>
 *
 * <h3>State testing vs interaction testing</h3>
 * <p>
 * The other tests in this project check <em>state</em>: call a method, look at the
 * returned value. A mock also allows checking <em>interactions</em>: did the code under
 * test talk to its collaborator, how many times, with what arguments? Prefer state
 * assertions when you can — they survive refactoring better. Interaction assertions are
 * the right tool when the interaction <em>is</em> the behaviour, as it is for a health
 * check whose whole job is "call the service once and report".
 * </p>
 *
 * <h3>A mock is not magic</h3>
 * <p>
 * The tests are ordered as a story. First a test double written by hand in a few lines
 * of plain Java, to show that a mock is just a class you could write yourself. Then the
 * identical test using Mockito, so you can see exactly what the framework replaces (the
 * class) and what it adds (recording and verification). Finally the annotation style
 * that most real codebases use.
 * </p>
 *
 * <h3>Reaching a private field</h3>
 * <p>
 * {@code GreetHealthCheck.greetService} is private and has no setter — OSGi's
 * Declarative Services injects it by reflection at run time. Our tests must do the
 * same, via {@link #healthCheckWith(IGreet)}. That small helper is a lesson in itself:
 * <em>field injection makes classes harder to test</em>. A constructor parameter or a
 * public setter would let the test hand over its double without reflection. The
 * {@code @InjectMocks} section at the end shows Mockito doing this reflection for you,
 * which is convenient — but it papers over the design smell rather than removing it.
 * </p>
 *
 * <h3>How Mockito runs inside OSGi</h3>
 * <p>
 * Mockito is a test-only dependency: the five bundles (Mockito, its JUnit adapter,
 * ByteBuddy, the ByteBuddy agent, Objenesis) are declared in the target platform and
 * pulled in by this fragment's {@code Require-Bundle}; they never enter the product.
 * Mockito 5 defaults to the "inline" mock maker, which attaches a Java agent to the
 * running JVM and rewrites the mocked class's bytecode in place instead of generating a
 * subclass. That is what lets it work across OSGi bundle classloaders without any
 * special wiring — and it is why the test log prints a warning about Mockito
 * "self-attaching". The warning is expected.
 * </p>
 */
public class GreetHealthCheckTest {

    // ---------------------------------------------------------------------------
    // 1. Why a double is needed: the real collaborator cannot fail
    // ---------------------------------------------------------------------------

    /**
     * Baseline with the real implementation. This passes, and it is a perfectly good
     * test of the happy path — but it is the <em>only</em> test the real {@link Greet}
     * can ever give us, because {@code Greet.greet()} cannot throw.
     */
    @Test
    void realImplementationReportsOk() throws Exception {
        GreetHealthCheck healthCheck = healthCheckWith(new Greet());

        Result result = healthCheck.execute();

        assertTrue(result.isOk());
        assertEquals(Result.Status.OK, result.getStatus());
    }

    // ---------------------------------------------------------------------------
    // 2. A test double written by hand
    // ---------------------------------------------------------------------------

    /**
     * The whole of a hand-written test double: an implementation of the collaborator's
     * interface that behaves exactly as the test needs. Six lines, no framework. Keep
     * this in mind whenever Mockito's syntax looks mysterious — underneath, it is
     * producing a class like this one.
     */
    static final class ThrowingGreet implements IGreet {
        @Override
        public void greet() {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * The {@code catch} branch, reached for the first time. With the double in place the
     * health check must report {@code CRITICAL} and include the exception's message in
     * its log — behaviour that was unverifiable a moment ago.
     */
    @Test
    void handWrittenDoubleForcesTheCatchBranch() throws Exception {
        GreetHealthCheck healthCheck = healthCheckWith(new ThrowingGreet());

        Result result = healthCheck.execute();

        assertEquals(Result.Status.CRITICAL, result.getStatus());
        assertTrue(messagesOf(result).contains("boom"),
                "the log should carry the exception message, got: " + messagesOf(result));
    }

    // ---------------------------------------------------------------------------
    // 3. The same thing with Mockito
    // ---------------------------------------------------------------------------

    /**
     * {@code mock(IGreet.class)} builds an implementation of the interface on the fly.
     * Nothing has been told about {@code greet()} yet, so it is <em>unstubbed</em> — and
     * an unstubbed method on a mock does the least surprising thing: nothing at all.
     * A void method returns silently; a method returning an object returns {@code null},
     * a number returns {@code 0}, a collection returns an empty one, a boolean returns
     * {@code false}. So this mock behaves like a {@link Greet} that has been silenced,
     * and the health check reports OK.
     */
    @Test
    void unstubbedMockDoesNothingAndReportsOk() throws Exception {
        IGreet greet = mock(IGreet.class);
        GreetHealthCheck healthCheck = healthCheckWith(greet);

        Result result = healthCheck.execute();

        assertEquals(Result.Status.OK, result.getStatus());
    }

    /**
     * Now the mock is <em>stubbed</em> to throw, and it replaces {@link ThrowingGreet}
     * exactly. Compare the two tests line by line: the only difference is that the
     * hand-written class became one {@code doThrow(...)} statement.
     *
     * <p>
     * <strong>The void-method gotcha.</strong> Mockito's most familiar form is
     * {@code when(greet.greet()).thenThrow(...)}. That does not compile here, and the
     * reason is worth understanding rather than memorising: {@code when(...)} takes the
     * <em>return value</em> of the call as its argument. {@code greet()} returns
     * {@code void}, so there is no value to pass — the Java compiler rejects it before
     * Mockito even runs. For void methods the call must therefore come <em>after</em>
     * the stubbing instruction: {@code doThrow(x).when(mock).greet()}. The
     * {@code do...().when(mock)} family works for non-void methods too; some teams use
     * it everywhere for consistency.
     * </p>
     */
    @Test
    void stubbedMockForcesTheCatchBranch() throws Exception {
        IGreet greet = mock(IGreet.class);
        doThrow(new IllegalStateException("boom")).when(greet).greet();
        GreetHealthCheck healthCheck = healthCheckWith(greet);

        Result result = healthCheck.execute();

        assertEquals(Result.Status.CRITICAL, result.getStatus());
        assertTrue(messagesOf(result).contains("boom"),
                "the log should carry the exception message, got: " + messagesOf(result));
    }

    // ---------------------------------------------------------------------------
    // 4. Interaction testing: what the hand-written double could not do
    // ---------------------------------------------------------------------------

    /**
     * Here Mockito earns its keep. {@link ThrowingGreet} could fake a result, but it
     * could not tell us <em>how it was used</em> without us adding counters by hand.
     * A mock records every call, and {@code verify} interrogates that record:
     * </p>
     * <ul>
     * <li>{@code verify(greet, times(1)).greet()} — the service was called exactly once.
     * {@code times(1)} is the default, so {@code verify(greet).greet()} means the same;
     * it is spelled out here because it is the form you will change to
     * {@code never()} or {@code atLeastOnce()} later.</li>
     * <li>{@code verifyNoMoreInteractions(greet)} — and nothing else was called on it.
     * Use this sparingly: it turns every future addition to the code into a test
     * failure. Here it is justified because a health check that called the service
     * twice would be doing twice the real work on every probe.</li>
     * </ul>
     * <p>
     * Note there is no {@code assert} in this test. The verification <em>is</em> the
     * assertion: {@code verify} throws if the record does not match.
     */
    @Test
    void healthCheckInvokesTheServiceExactlyOnce() throws Exception {
        IGreet greet = mock(IGreet.class);
        GreetHealthCheck healthCheck = healthCheckWith(greet);

        healthCheck.execute();

        verify(greet, times(1)).greet();
        verifyNoMoreInteractions(greet);
    }

    // ---------------------------------------------------------------------------
    // 5. The annotation style used in most real codebases
    // ---------------------------------------------------------------------------

    /**
     * Everything above, rewritten the way you will usually meet it. Three annotations
     * replace the manual wiring:
     * <ul>
     * <li>{@code @ExtendWith(MockitoExtension.class)} — plugs Mockito into JUnit 5's
     * lifecycle so the other two annotations are processed before each test. It also
     * enables <em>strict stubs</em>: a stub that a test sets up but never uses fails
     * that test, which catches copy-pasted setup that no longer means anything.</li>
     * <li>{@code @Mock} — the field is assigned {@code mock(IGreet.class)}.</li>
     * <li>{@code @InjectMocks} — the field is assigned {@code new GreetHealthCheck()},
     * and Mockito then pushes every {@code @Mock} into a matching field by type — the
     * same reflection {@link #healthCheckWith(IGreet)} does by hand, and the same thing
     * Declarative Services does at run time.</li>
     * </ul>
     * <p>
     * This lives in a {@code @Nested} class so the extension applies only here; the
     * tests above deliberately show the wiring in the open. Each nested test still gets
     * a fresh outer instance, fresh mocks and a fresh health check.
     * </p>
     * <p>
     * A caution about {@code @InjectMocks}: it is silent when it fails. If the field
     * name or type changes so that nothing matches, the mock is simply not injected and
     * the test crashes with a {@code NullPointerException} in the code under test —
     * confusing until you know to look here first.
     * </p>
     */
    @Nested
    @ExtendWith(MockitoExtension.class)
    class WithAnnotations {

        @Mock
        IGreet greet;

        @InjectMocks
        GreetHealthCheck healthCheck;

        @Test
        void stubbedMockForcesTheCatchBranch() {
            doThrow(new IllegalStateException("boom")).when(greet).greet();

            Result result = healthCheck.execute();

            assertEquals(Result.Status.CRITICAL, result.getStatus());
            assertTrue(messagesOf(result).contains("boom"));
            verify(greet).greet();
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Creates a health check with the given {@link IGreet} in its private
     * {@code greetService} field, standing in for OSGi's run-time injection. This is the
     * cost of field injection without a setter: a test has to reach in by reflection.
     * It works here because this fragment shares the {@code imp} bundle's classloader,
     * so nothing stands between us and the class's internals.
     */
    private static GreetHealthCheck healthCheckWith(IGreet greet) throws Exception {
        GreetHealthCheck healthCheck = new GreetHealthCheck();
        Field field = GreetHealthCheck.class.getDeclaredField("greetService");
        field.setAccessible(true);
        field.set(healthCheck, greet);
        return healthCheck;
    }

    /** Joins every log entry's message so a test can assert on the log's content. */
    private static String messagesOf(Result result) {
        StringBuilder messages = new StringBuilder();
        for (ResultLog.Entry entry : result) {
            messages.append(entry.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
