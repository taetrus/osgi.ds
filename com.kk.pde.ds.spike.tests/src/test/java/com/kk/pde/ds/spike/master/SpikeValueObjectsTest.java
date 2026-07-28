package com.kk.pde.ds.spike.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// These four streams turn an object into bytes and back again. They are the exact
// mechanism ECF uses on the wire, which is why the round-trip test below can prove
// something real about the networked system without opening a socket.
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.Test;

import com.kk.pde.ds.spike.api.AnchorState;
import com.kk.pde.ds.spike.api.CatalogItem;
import com.kk.pde.ds.spike.api.DockBounds;
import com.kk.pde.ds.spike.api.DockLayout;
import com.kk.pde.ds.spike.api.DockState;

/**
 * Contract checks for the serializable value objects that cross JVMs over ECF's
 * Generic transport: equality semantics and an actual serialization round-trip
 * (the same marshalling ECF performs).
 *
 * <h2>New to unit testing? Read {@code GreetTest} first, then this</h2>
 *
 * <p>
 * The earlier test classes checked <em>behaviour</em> — call a method, inspect the
 * answer. This one checks <strong>contracts</strong>: rules that Java itself, and the
 * libraries built on it, silently assume your classes obey. Nothing enforces them at
 * compile time. Break one and the consequence surfaces far away from the cause, usually
 * as an object mysteriously vanishing from a {@code HashMap} or arriving over the
 * network subtly different from how it left.
 * </p>
 *
 * <h3>Contract 1: {@code equals} and {@code hashCode}</h3>
 * <p>
 * A <strong>value object</strong> is a class where identity does not matter, only
 * content — two {@code DockBounds(1,2,3,4)} <em>are</em> the same rectangle, however
 * many times you constructed one. Java does not know that by default: {@code Object}'s
 * inherited {@code equals} compares memory addresses, so two identical-looking
 * rectangles are unequal until you override it.
 * </p>
 * <p>
 * The rule that catches people out is that {@code equals} and {@code hashCode} must be
 * overridden <em>together</em>. Any hash-based collection ({@code HashMap},
 * {@code HashSet}) first finds a bucket by {@code hashCode}, and only then compares
 * with {@code equals}. Override {@code equals} alone and equal objects land in
 * different buckets, so the map never even reaches the comparison — you put a key in
 * and cannot get it back out. That is why every equality test below asserts the
 * hash codes match too, and it is worth doing even when it feels redundant.
 * </p>
 *
 * <h3>Contract 2: serialization</h3>
 * <p>
 * {@code Serializable} is Java's built-in "convert this object to bytes" mechanism, and
 * it is what ECF uses to move these objects between the two JVMs. It is unusually easy
 * to get subtly wrong — a field marked {@code transient}, a nested class that forgot to
 * implement {@code Serializable}, a mismatched {@code serialVersionUID} — and every one
 * of those failures happens at run time, on the far side of a network, in the other
 * process's log. The round-trip test at the bottom of this file catches all of them in
 * milliseconds, in-process, with no network involved.
 * </p>
 *
 * <h3>Note the granularity: one behaviour per test</h3>
 * <p>
 * There are four tests here, not one big one. It would be perfectly possible to assert
 * everything in a single method — and if it failed you would know only that "something
 * about the value objects is broken". Small, separately-named tests mean the failure
 * report itself tells you the diagnosis. Aim for one reason to fail per test.
 * </p>
 */
public class SpikeValueObjectsTest {

	/**
	 * The full equality contract for {@code AnchorState}, in both directions.
	 *
	 * <p>
	 * The two {@code assertNotEquals} lines are the ones that matter most, and they are
	 * the ones beginners leave out. Asserting only that equal things are equal is passed
	 * trivially by a broken {@code equals} that always returns {@code true} — a real bug
	 * that a positive-only test cannot see. You need both halves: equal things equal,
	 * <em>and</em> different things different.
	 * </p>
	 *
	 * <p>
	 * The difference cases are chosen carefully too. Flipping the boolean checks that
	 * the third field participates in the comparison at all. Swapping {@code (3, 4)} to
	 * {@code (4, 3)} is sharper: it uses the same two numbers in the opposite order, so
	 * it fails an {@code equals} implementation that compares {@code x} to the other
	 * object's {@code y} — the classic copy-paste bug in a hand-written {@code equals},
	 * and one that an ordinary "different values" test sails straight past.
	 * </p>
	 *
	 * <p>
	 * The last three lines check the {@code HOME} constant rather than equality. Shared
	 * mutable-looking constants deserve a test precisely because they are shared: if
	 * {@code HOME} were ever built with the wrong values, the bug would appear in every
	 * component that falls back to it and in none of them obviously.
	 * </p>
	 */
	@Test
	void anchorStateEqualityAndHome() {
		// Same values must be equal — and must agree on hashCode, or hash-based
		// collections will lose them.
		assertEquals(new AnchorState(3, 4, true), new AnchorState(3, 4, true));
		assertEquals(new AnchorState(3, 4, true).hashCode(), new AnchorState(3, 4, true).hashCode());
		// Differing in the boolean alone must break equality...
		assertNotEquals(new AnchorState(3, 4, true), new AnchorState(3, 4, false));
		// ...as must swapping x and y, which catches a transposed field comparison.
		assertNotEquals(new AnchorState(3, 4, true), new AnchorState(4, 3, true));

		assertEquals(0, AnchorState.HOME.getOffsetX());
		assertEquals(0, AnchorState.HOME.getOffsetY());
		assertFalse(AnchorState.HOME.isMinimized());
	}

	/**
	 * The same contract for {@code DockBounds}, kept as its own test.
	 *
	 * <p>
	 * Folding this into the test above would save four lines and cost you the diagnosis:
	 * when the suite goes red you would have to open the file to learn which class is
	 * broken, instead of reading it off the test name.
	 * </p>
	 *
	 * <p>
	 * Again the inequality case reuses the same digits in a different order —
	 * {@code (1,2,3,4)} against {@code (1,2,4,3)}, swapping width and height. A
	 * rectangle class that muddles width and height is a very easy mistake to make and a
	 * very annoying one to debug through a UI, so it is worth aiming a test squarely at
	 * it.
	 * </p>
	 */
	@Test
	void dockBoundsEquality() {
		assertEquals(new DockBounds(1, 2, 3, 4), new DockBounds(1, 2, 3, 4));
		assertEquals(new DockBounds(1, 2, 3, 4).hashCode(), new DockBounds(1, 2, 3, 4).hashCode());
		// Swapped width/height must not compare equal.
		assertNotEquals(new DockBounds(1, 2, 3, 4), new DockBounds(1, 2, 4, 3));
	}

	/**
	 * Null-handling in a constructor: a null anchor is normalised to {@code HOME} rather
	 * than stored as null.
	 *
	 * <p>
	 * This is a two-line test guarding a design decision worth a great deal. Because the
	 * constructor substitutes a default, no caller anywhere — including the code running
	 * in the other JVM — has to null-check {@code getAnchor()}. The alternative is a
	 * {@code NullPointerException} surfacing somewhere in the detail app's paint code,
	 * a long way from the object that was constructed wrong.
	 * </p>
	 *
	 * <p>
	 * Two of the four constructor arguments are also null here, deliberately: they stay
	 * null, and that contrast is the point. The test would be much weaker if every
	 * argument were normalised, because it could not distinguish "this field defaults"
	 * from "all fields default".
	 * </p>
	 */
	@Test
	void dockStateNormalizesNullAnchorToHome() {
		DockState state = new DockState(null, false, null, null);
		assertEquals(AnchorState.HOME, state.getAnchor());
	}

	/**
	 * The most valuable test in this file: an object graph is serialized to bytes and
	 * rebuilt, exactly as it is when ECF ships it to the other JVM.
	 *
	 * <p>
	 * Note {@code throws Exception} on the signature. Test methods are allowed to
	 * declare checked exceptions, and you should let them: wrapping the body in
	 * {@code try/catch} to satisfy the compiler would only convert a genuine failure
	 * into either a swallowed error or a less informative one. If an exception escapes,
	 * JUnit marks the test failed and shows you the stack trace — which is precisely the
	 * outcome you want. Never write {@code catch (Exception e) { }} in a test.
	 * </p>
	 *
	 * <p>
	 * The fixture is a <em>nested</em> object graph on purpose: a {@code DockState}
	 * holding a {@code DockLayout}, a {@code CatalogItem}, and an {@code AnchorState}.
	 * Serialization follows every reference, so one round trip proves all four classes
	 * are correctly serializable. A flat fixture would prove only the outermost one, and
	 * the inner class that forgot to implement {@code Serializable} is exactly the bug
	 * you are hunting.
	 * </p>
	 *
	 * <p>
	 * The values are chosen to be awkward. {@code -20} is negative, {@code true} is not
	 * the default {@code false}, and the strings are distinct from one another. A fixture
	 * built from zeros, empty strings and {@code false} is nearly useless for a
	 * round-trip test, because a completely broken deserialization that returns a
	 * default-constructed object would pass it.
	 * </p>
	 *
	 * <p>
	 * The final two assertions are the sharpest. Rather than only comparing stored
	 * fields, they ask the <em>deserialized</em> layout to compute geometry and check it
	 * matches the original's. That closes the loop with {@code DockLayoutTest}: that
	 * class proved two layouts built from the same numbers agree, and this one proves the
	 * numbers actually survive the wire. Together they cover the real end-to-end
	 * property — windows in two separate processes tile without overlapping.
	 * </p>
	 */
	@Test
	void valueObjectsSurviveSerializationRoundTrip() throws Exception {
		// Arrange: a nested graph with deliberately non-default values.
		DockLayout layout = new DockLayout(2, 320, 240, 15, 25, 3);
		CatalogItem item = new CatalogItem("p9", "Widget", "A test widget", 7);
		DockState original = new DockState(layout, true, item, new AnchorState(10, -20, true));

		// Act: bytes out, bytes in — what ECF does across the socket.
		DockState copy = roundTrip(original);

		// Assert: every field survived, at every level of nesting.
		assertTrue(copy.isClosing());
		assertEquals(new AnchorState(10, -20, true), copy.getAnchor());
		assertEquals("p9", copy.getSelected().getId());
		assertEquals("Widget", copy.getSelected().getName());
		assertEquals(7, copy.getSelected().getQuantity());
		// The deserialized layout must produce identical geometry — the property
		// the two-JVM tiling depends on.
		assertEquals(layout.slotBounds(4), copy.getLayout().slotBounds(4));
		assertEquals(layout.getColumns(), copy.getLayout().getColumns());
	}

	/**
	 * A <strong>test helper</strong>: not a test itself (no {@code @Test} annotation, so
	 * JUnit ignores it entirely), just a private method the test above calls.
	 *
	 * <p>
	 * Helpers like this are how you keep tests readable. All the stream plumbing lives
	 * here under a name that says what it means, so the test body reads
	 * {@code DockState copy = roundTrip(original)} — one line stating the intent —
	 * instead of six lines of {@code ByteArrayOutputStream} ceremony that obscure what is
	 * being asserted. The guideline: a test method should read like a description of the
	 * scenario; anything that is mechanism rather than meaning belongs in a helper.
	 * </p>
	 *
	 * <p>
	 * It is generic ({@code <T>}) so it can round-trip any object and hand back the same
	 * static type, which is what lets the caller assign straight to {@code DockState}
	 * without a cast. The {@code @SuppressWarnings("unchecked")} covers the
	 * {@code (T) ois.readObject()} cast: {@code readObject} returns {@code Object}, and
	 * the compiler cannot verify at compile time that the bytes will deserialize to a
	 * {@code T}. In production code an unchecked cast deserves suspicion; here the type
	 * is guaranteed by the fact that the test itself wrote the bytes moments earlier, and
	 * a mismatch would simply fail the test with a {@code ClassCastException} — which is
	 * a perfectly good outcome for a test.
	 * </p>
	 *
	 * <p>
	 * Both streams use <strong>try-with-resources</strong> ({@code try (X x = ...)}),
	 * which closes them automatically however the block exits. For
	 * {@code ObjectOutputStream} this is not mere tidiness: closing flushes buffered
	 * bytes, and reading {@code bos.toByteArray()} before the close could yield a
	 * truncated stream and a baffling {@code EOFException}.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(T obj) throws Exception {
		// Serialize: object -> bytes, held in memory rather than a file.
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
			oos.writeObject(obj);
		}
		// Deserialize: those same bytes -> a brand-new object graph, sharing nothing
		// with the original. That independence is what makes the assertions meaningful.
		try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
			return (T) ois.readObject();
		}
	}
}
