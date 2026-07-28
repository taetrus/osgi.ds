package com.kk.pde.ds.spike.master;

// Static imports of the assertion methods, so they can be called by bare name.
// Each one states a different kind of demand:
//   assertEquals(expected, actual) - the two values are equal via .equals()
//   assertSame(expected, actual)   - the two references point at the SAME object
//   assertNull(value)              - the value is null
//   assertTrue / assertFalse       - the boolean is true / false
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kk.pde.ds.spike.api.AnchorState;
import com.kk.pde.ds.spike.api.CatalogItem;
import com.kk.pde.ds.spike.api.DockLayout;
import com.kk.pde.ds.spike.api.DockState;

/**
 * State behaviour of the master-side catalog service — the single source of truth
 * the detail JVM polls over ECF. Instantiated directly (the same calls DS would
 * make); {@code requestShutdown()} is deliberately untested because it exits the JVM.
 *
 * <h2>New to unit testing? Read {@code GreetTest} first, then this</h2>
 *
 * <p>
 * {@code GreetTest} tests a method with no memory: same input, same output, forever.
 * {@code CatalogServiceImpl} is the opposite — it is a <strong>stateful</strong>
 * object. Calling {@code setSelectedId("p2")} changes what a later
 * {@code getSelectedItem()} returns. Testing stateful objects introduces two ideas
 * this file demonstrates.
 * </p>
 *
 * <h3>1. Shared setup with {@code @BeforeEach}</h3>
 * <p>
 * Every test here needs a started service. Rather than repeat those two lines eleven
 * times, they live in one {@code @BeforeEach} method that JUnit calls automatically
 * before each test. Crucially this runs <em>before each test, not once for all of
 * them</em> — combined with JUnit creating a fresh instance of this class per test,
 * every test gets its own private, untouched service. That isolation is what lets you
 * mutate state freely in one test without breaking the next.
 * </p>
 * <p>
 * (The counterpart {@code @BeforeAll} runs once for the whole class. Reach for it only
 * for genuinely expensive, genuinely read-only setup — it re-introduces exactly the
 * cross-test coupling {@code @BeforeEach} exists to prevent.)
 * </p>
 *
 * <h3>2. Test the contract, not the implementation</h3>
 * <p>
 * Notice that these tests only ever call public methods — they never reach inside the
 * service to inspect its fields. That is the point. A test coupled to internals breaks
 * every time you refactor, even when behaviour is unchanged, and a test that cries
 * wolf gets deleted. A test coupled only to the public contract keeps working through
 * any rewrite that preserves that contract, which is precisely the safety net you
 * wanted.
 * </p>
 *
 * <h3>Why this test can call {@code new CatalogServiceImpl()} directly</h3>
 * <p>
 * At run time this class is an OSGi Declarative Services component: the SCR runtime
 * constructs it, injects its references, and calls its lifecycle methods. None of that
 * is needed here. DS is just a container calling ordinary Java methods, so the test
 * makes the same calls by hand — {@code new}, then {@code start()}. Keeping components
 * constructible without a framework is a deliberate design property, and it is what
 * makes them cheap to test.
 * </p>
 */
public class CatalogServiceImplTest {

	/**
	 * Held in a field so {@code @BeforeEach} can assign it and every {@code @Test} can
	 * read it. This is safe <em>only</em> because JUnit builds a new instance of this
	 * class per test — in effect each test gets its own copy of this field. In a class
	 * with normal (non-test) semantics, sharing mutable state through a field like this
	 * across methods would be a bug waiting to happen.
	 */
	private CatalogServiceImpl service;

	/**
	 * Runs before <em>every</em> {@code @Test} method in this class, giving each one a
	 * fresh, freshly-started service.
	 *
	 * <p>
	 * The name is arbitrary — JUnit finds this method by the {@code @BeforeEach}
	 * annotation, not by its name. It is called {@code activate} here because it mirrors
	 * what the DS runtime does to this component in production: construct, then start.
	 * </p>
	 */
	@BeforeEach
	void activate() {
		service = new CatalogServiceImpl();
		service.start();
	}

	/**
	 * Establishes the baseline every other test assumes: {@code start()} populates the
	 * catalog with the five demo items.
	 *
	 * <p>
	 * Worth noticing what this test checks and what it does not. It asserts the item
	 * <em>count</em> and the <em>first id</em> — enough to prove seeding happened and
	 * that order is stable — rather than asserting all five items field by field. A test
	 * that pinned down every value would fail the moment someone added a sixth demo item,
	 * reporting a "failure" that is really just a changed fixture. Assert the property
	 * you actually depend on, not everything you happen to be able to see.
	 * </p>
	 */
	@Test
	void activationSeedsTheCatalog() {
		List<CatalogItem> items = service.listItems();
		assertEquals(5, items.size());
		assertEquals("p1", items.get(0).getId());
	}

	/**
	 * A <strong>defensive copy</strong> test, and a good example of a test that looks
	 * odd until you know the bug it prevents.
	 *
	 * <p>
	 * If {@code listItems()} handed back its internal list directly, any caller could
	 * mutate the service's own state just by touching the returned list — the service
	 * would silently lose its catalog and nobody would know where it went. So the test
	 * deliberately performs that attack: it calls {@code .clear()} on the returned list
	 * and then asserts the service is unharmed. It passes only if {@code listItems()}
	 * returns a copy.
	 * </p>
	 *
	 * <p>
	 * Note the third argument to {@code assertEquals}: an optional <em>message</em>
	 * shown when the assertion fails. Use it whenever the assertion alone would not
	 * explain itself. Here, a bare "expected 5 but was 0" would be baffling; the message
	 * tells the next reader exactly what invariant broke.
	 * </p>
	 */
	@Test
	void listItemsReturnsADefensiveCopy() {
		service.listItems().clear();
		assertEquals(5, service.listItems().size(), "mutating the returned list must not touch service state");
	}

	/** The happy path for lookup: a known id resolves to the right item. */
	@Test
	void getItemFindsById() {
		CatalogItem item = service.getItem("p3");
		assertEquals("O-Ring 12mm", item.getName());
	}

	/**
	 * The unhappy path, and just as important as the happy one above.
	 *
	 * <p>
	 * "What does this do when asked for something that isn't there?" has several
	 * defensible answers — return null, throw, return an empty Optional — and callers
	 * must be written against one of them. This test picks null and pins it down. Once
	 * pinned, a future refactor that starts throwing instead cannot slip through
	 * unnoticed and surprise every caller at run time.
	 * </p>
	 */
	@Test
	void getItemReturnsNullForUnknownId() {
		assertNull(service.getItem("nope"));
	}

	/**
	 * The initial state, asserted explicitly.
	 *
	 * <p>
	 * It is tempting to skip a test like this — "of course nothing is selected yet". But
	 * every other test in this class is written on the assumption that a fresh service
	 * starts with an empty selection, so that assumption deserves a test of its own
	 * rather than being left implicit in ten others.
	 * </p>
	 */
	@Test
	void selectionStartsEmpty() {
		assertNull(service.getSelectedId());
		assertNull(service.getSelectedItem());
	}

	/**
	 * "Round trip" — write a value in, read it back out, confirm it survived.
	 *
	 * <p>
	 * This is the canonical shape for testing any setter/getter pair, and here it covers
	 * two things at once: the id is stored verbatim, and the <em>derived</em>
	 * {@code getSelectedItem()} resolves that id to the matching catalog entry.
	 * </p>
	 */
	@Test
	void selectionRoundTrips() {
		service.setSelectedId("p2");
		assertEquals("p2", service.getSelectedId());
		assertEquals("Ball Bearing", service.getSelectedItem().getName());
	}

	/**
	 * The interesting corner of the pair above: what happens when the two getters
	 * disagree?
	 *
	 * <p>
	 * Selecting an id that no item has must <em>not</em> be silently rejected — the id
	 * is remembered exactly as given, while the resolved item is null. That combination
	 * matters in the real system: the detail JVM polls this state over the network and
	 * can legitimately observe an id whose item has not arrived yet, so it must cope
	 * with "id set, item null" rather than assuming the two always agree.
	 * </p>
	 */
	@Test
	void selectingAnUnknownIdYieldsNoSelectedItem() {
		service.setSelectedId("ghost");
		assertEquals("ghost", service.getSelectedId());
		assertNull(service.getSelectedItem());
	}

	/**
	 * The longest test here, and the first with a clearly separated
	 * <strong>Arrange / Act / Assert</strong> structure — four setter calls to build up
	 * a state, one call to snapshot it, then four assertions on the snapshot.
	 *
	 * <p>
	 * Note {@code assertSame} on the layout, where every other assertion uses
	 * {@code assertEquals}. {@code assertSame} is the stricter demand: not merely "an
	 * equal layout" but "the very object I passed in, same identity". That is the right
	 * assertion here because {@code getDockState()} is supposed to bundle up the
	 * existing state, not rebuild or copy it — and {@code assertEquals} would happily
	 * pass on a defensive copy, letting a needless allocation go unnoticed.
	 * </p>
	 *
	 * <p>
	 * The anchor, by contrast, is checked with {@code assertEquals} against a
	 * <em>newly constructed</em> {@code new AnchorState(42, -7, true)}. That works only
	 * because {@code AnchorState} implements {@code equals()} by value — which is
	 * exactly what {@code SpikeValueObjectsTest} exists to guarantee. Tests lean on
	 * other tests' guarantees like this all the time.
	 * </p>
	 */
	@Test
	void dockStateBundlesTheFullSnapshot() {
		// Arrange: drive the service into a fully populated state.
		DockLayout layout = new DockLayout(2, 300, 200, 10, 20, 4);
		service.setLayout(layout);
		service.setSelectedId("p5");
		service.setClosing(true);
		service.setAnchor(new AnchorState(42, -7, true));

		// Act: take the snapshot that the detail JVM would receive.
		DockState state = service.getDockState();

		// Assert: every piece of the state made it into the snapshot intact.
		assertSame(layout, state.getLayout());
		assertTrue(state.isClosing());
		assertEquals("p5", state.getSelected().getId());
		assertEquals(new AnchorState(42, -7, true), state.getAnchor());
	}

	/**
	 * The mirror image of the test above: the snapshot taken before anything has been
	 * published must still be a usable object.
	 *
	 * <p>
	 * This is the real-world race the spike has to survive — the detail JVM can start
	 * polling before the master has measured the screen and published a grid. So
	 * {@code getDockState()} must return a well-formed, neutral snapshot rather than
	 * null or a half-built object that blows up on the other side of the wire.
	 * </p>
	 *
	 * <p>
	 * Notice the two different expectations for "nothing here yet": the layout is
	 * genuinely {@code null} (there is no sensible default grid to invent), while the
	 * anchor falls back to the {@code AnchorState.HOME} constant. Both are deliberate,
	 * and asserting both is what stops someone from "helpfully" making them consistent
	 * later and breaking a caller that relies on the difference.
	 * </p>
	 */
	@Test
	void dockStateBeforeAnyPublishIsTheNeutralDefault() {
		DockState state = service.getDockState();
		assertNull(state.getLayout(), "layout is null until the master publishes the grid");
		assertFalse(state.isClosing());
		assertNull(state.getSelected());
		assertEquals(AnchorState.HOME, state.getAnchor());
	}

	/**
	 * Null handling, tested the awkward way round on purpose.
	 *
	 * <p>
	 * The test does not simply pass null to a fresh service — that would also pass if
	 * the anchor had merely never been set. It first sets a <em>real</em> anchor, then
	 * sets null, and only then asserts the fallback. That ordering is what proves null
	 * actively resets the value to {@code HOME}, rather than being quietly ignored and
	 * leaving the previous anchor in place.
	 * </p>
	 *
	 * <p>
	 * The general lesson: when you assert a value, make sure the test would have failed
	 * had the code done nothing at all. An assertion that passes for the wrong reason is
	 * worse than no assertion, because it buys false confidence.
	 * </p>
	 */
	@Test
	void nullAnchorFallsBackToHome() {
		service.setAnchor(new AnchorState(5, 5, false));
		service.setAnchor(null);
		assertEquals(AnchorState.HOME, service.getDockState().getAnchor());
	}
}
