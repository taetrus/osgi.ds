package com.kk.pde.ds.spike.master;

// assertArrayEquals is the one to notice here: arrays must NOT be compared with
// assertEquals. See the comment on distributeSplitsPanelsIntoBalancedContiguousChunks
// below for why that mistake produces a test that fails even when the code is correct.
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kk.pde.ds.spike.api.DockBounds;
import com.kk.pde.ds.spike.api.DockLayout;

/**
 * The grid math both JVMs must agree on: slot interleaving, slot-to-bounds
 * mapping, and the balanced contiguous panel distribution. Any drift here shows
 * up at runtime as overlapping or mis-tiled windows across the two apps.
 *
 * <h2>New to unit testing? Read {@code GreetTest} first, then this</h2>
 *
 * <p>
 * {@code DockLayout} is a <strong>pure calculation</strong>: same numbers in, same
 * geometry out, no files, no network, no clock, no state. Pure code is the easiest
 * thing in the world to test — there is nothing to set up and nothing to clean up —
 * which is exactly why it is worth extracting arithmetic like this out of the UI
 * classes that use it. The Swing frames that consume these results would need a running
 * screen to test; the arithmetic they depend on does not.
 * </p>
 *
 * <h3>Why this class is worth testing at all</h3>
 * <p>
 * The stakes here are unusual and they explain the shape of these tests. Two
 * <em>separate JVMs</em> each compute window positions from the same handful of
 * numbers, and they never compare notes afterwards. Nothing at run time detects a
 * disagreement — the failure mode is simply that a user sees two windows drawn on top
 * of each other. There is no exception, no stack trace, no log line. When a bug cannot
 * announce itself at run time, a test is the only thing that will ever catch it.
 * </p>
 *
 * <h3>The pattern to take away: happy path first, then the edges</h3>
 * <p>
 * Read the tests in order and you will see a deliberate progression. The first few
 * cover normal inputs. The last four cover the awkward ones — zero columns, more frames
 * than panels, one frame, zero panels, negative frames. That ordering is the habit
 * worth stealing:
 * </p>
 * <ol>
 * <li>Prove the ordinary case works.</li>
 * <li>Then ask "what values would embarrass this code?" — zero, one, negative, empty,
 * bigger-than-expected — and write one test per answer.</li>
 * </ol>
 * <p>
 * Bugs cluster at boundaries. Off-by-one errors, division by zero, and empty-collection
 * crashes essentially never appear in the middle of the input range.
 * </p>
 */
public class DockLayoutTest {

	/**
	 * The interleaving rule, checked at three points rather than one.
	 *
	 * <p>
	 * Master frames occupy even slots, detail frames odd ones. A single pair
	 * ({@code masterSlot(0) == 0}) would be weak evidence — plenty of wrong formulas
	 * also return 0 for input 0. Checking frames 0, 1, and 4 pins down the
	 * <em>pattern</em> rather than a single point on it, and frame 4 in particular
	 * catches a formula that happens to be right only for small inputs.
	 * </p>
	 *
	 * <p>
	 * There is no "arrange" step at all here: {@code masterSlot} and {@code detailSlot}
	 * are {@code static}, so there is nothing to construct. Testing static pure
	 * functions is as simple as testing gets.
	 * </p>
	 */
	@Test
	void masterAndDetailSlotsInterleave() {
		// Fill order is M0, D0, M1, D1, ... — master owns even slots, detail odd.
		assertEquals(0, DockLayout.masterSlot(0));
		assertEquals(1, DockLayout.detailSlot(0));
		assertEquals(2, DockLayout.masterSlot(1));
		assertEquals(3, DockLayout.detailSlot(1));
		assertEquals(8, DockLayout.masterSlot(4));
		assertEquals(9, DockLayout.detailSlot(4));
	}

	/**
	 * Slot-index to screen-rectangle mapping, including the wrap to a new row.
	 *
	 * <p>
	 * The constructor arguments are, in order: {@code framesPerApp=2},
	 * {@code tileWidth=200}, {@code tileHeight=150}, {@code originX=100},
	 * {@code originY=50}, {@code columns=3}. Six bare integers in a row is genuinely
	 * hard to read, which is precisely why the expected rectangles below are written out
	 * as literal numbers rather than recomputed in the test.
	 * </p>
	 *
	 * <p>
	 * That last point is a rule worth internalising: <strong>never compute the expected
	 * value using the same formula as the code under test</strong>. Writing
	 * {@code assertEquals(new DockBounds(originX + col * tileWidth, ...), ...)} would
	 * produce a test that passes even if the formula is completely wrong, because both
	 * sides would be wrong in the same way. Hand-calculated constants are the whole
	 * point — they are an independent second opinion.
	 * </p>
	 *
	 * <p>
	 * The chosen values also make errors legible. Origin (100, 50) is non-zero, so a bug
	 * that forgets to add the origin cannot hide — with an origin of (0, 0) it would
	 * produce the right answer by accident. Similarly the width (200) differs from the
	 * height (150), so swapping the two is immediately visible. Picking fixture values
	 * that make bugs <em>distinguishable</em> is a real skill.
	 * </p>
	 */
	@Test
	void slotBoundsFlowLeftToRightAndWrap() {
		DockLayout layout = new DockLayout(2, 200, 150, 100, 50, 3);

		// Row 0: slots 0..2 march right from the origin.
		assertEquals(new DockBounds(100, 50, 200, 150), layout.slotBounds(0));
		assertEquals(new DockBounds(300, 50, 200, 150), layout.slotBounds(1));
		assertEquals(new DockBounds(500, 50, 200, 150), layout.slotBounds(2));
		// Slot 3 wraps to row 1, back at the origin column.
		assertEquals(new DockBounds(100, 200, 200, 150), layout.slotBounds(3));
		// Slot 4: col 1, row 1.
		assertEquals(new DockBounds(300, 200, 200, 150), layout.slotBounds(4));
	}

	/**
	 * The two properties the whole two-JVM design rests on, asserted directly:
	 * <strong>agreement</strong> and <strong>non-overlap</strong>.
	 *
	 * <p>
	 * This test is a different species from the ones above. Those checked specific
	 * inputs against specific hand-calculated outputs. This one asserts a
	 * <em>property</em> that must hold across a whole range of inputs, using nested
	 * loops: for every frame, master and detail agree on master's geometry, and master's
	 * rectangle never coincides with any of detail's. That style — sometimes called
	 * property-based thinking — is the right tool when the invariant matters more than
	 * any individual value.
	 * </p>
	 *
	 * <p>
	 * The two independently constructed {@code DockLayout} instances are the test
	 * standing in for the two JVMs. In production one of these objects is serialized,
	 * shipped over an ECF socket, and rebuilt in another process; here they are simply
	 * built twice from the same numbers, which exercises the same guarantee without
	 * needing a network. (The serialization step itself is covered separately in
	 * {@code SpikeValueObjectsTest} — each test isolates one link in the chain.)
	 * </p>
	 *
	 * <p>
	 * {@code assertNotEquals} is the mirror of {@code assertEquals}: it fails if the two
	 * values <em>are</em> equal. Asserting that something must not happen is easy to
	 * forget, and here it is carrying the most important guarantee in the file — two
	 * windows must never land on the same rectangle.
	 * </p>
	 */
	@Test
	void bothAppsComputeIdenticalBoundsFromSharedNumbers() {
		// The whole point of DockLayout: two independent instances built from the
		// same numbers (as after ECF serialization) yield identical geometry.
		DockLayout master = new DockLayout(3, 320, 240, 0, 0, 4);
		DockLayout detail = new DockLayout(3, 320, 240, 0, 0, 4);
		for (int frame = 0; frame < 3; frame++) {
			assertEquals(master.slotBounds(DockLayout.masterSlot(frame)),
					detail.slotBounds(DockLayout.masterSlot(frame)));
			// Master's slot never collides with detail's slot for any frame pair.
			for (int other = 0; other < 3; other++) {
				assertNotEquals(master.slotBounds(DockLayout.masterSlot(frame)),
						detail.slotBounds(DockLayout.detailSlot(other)));
			}
		}
	}

	/**
	 * The first edge case: a caller passes zero columns.
	 *
	 * <p>
	 * {@code slotBounds} computes {@code globalSlot % columns} and
	 * {@code globalSlot / columns}. With {@code columns == 0} both would throw
	 * {@link ArithmeticException} — a crash in the UI thread at start-up. The
	 * constructor therefore clamps with {@code Math.max(1, columns)}, and this test is
	 * what keeps that clamp from being "tidied away" by someone who cannot see why it is
	 * there.
	 * </p>
	 *
	 * <p>
	 * Note that it asserts the clamp two ways: the getter reports 1, <em>and</em> the
	 * geometry that depends on it is still correct (with one column, slot 2 sits two
	 * rows down at y=200). Checking only the getter would leave open the possibility
	 * that the clamped field is stored but some other code path still divides by the
	 * original zero. Assert the consequence, not just the mechanism.
	 * </p>
	 */
	@Test
	void columnsAreClampedToAtLeastOne() {
		DockLayout layout = new DockLayout(1, 100, 100, 0, 0, 0);
		assertEquals(1, layout.getColumns());
		// With one column everything stacks vertically instead of dividing by zero.
		assertEquals(new DockBounds(0, 200, 100, 100), layout.slotBounds(2));
	}

	/**
	 * {@code distribute} spreads N panels across M frames. This is the happy path, and
	 * the first test in the file to compare arrays.
	 *
	 * <p>
	 * <strong>The array trap.</strong> Java arrays do not override {@code equals()} —
	 * {@code new int[]{0,0}.equals(new int[]{0,0})} is {@code false}, because arrays
	 * inherit {@code Object.equals}, which compares identity. So
	 * {@code assertEquals(new int[]{0,0,1,1}, actual)} would fail <em>even when the code
	 * is perfectly correct</em>, and the failure message ("expected [I@1b6d3586 but was
	 * [I@4554617c") gives no hint why. {@code assertArrayEquals} compares element by
	 * element, which is what you meant. The same trap applies to
	 * {@code assertEquals(expected, actual)} on any array anywhere in Java.
	 * </p>
	 *
	 * <p>
	 * The three cases are chosen to distinguish the two plausible algorithms. Even
	 * division ({@code 4 over 2}) would pass under either a contiguous or a round-robin
	 * implementation — {@code {0,0,1,1}} versus {@code {0,1,0,1}} differ, so it does
	 * discriminate, but the uneven case ({@code 5 over 2}) additionally pins down
	 * <em>which</em> chunk absorbs the remainder. "Larger chunk first" versus "larger
	 * chunk last" is exactly the kind of unstated decision that a future refactor flips
	 * without noticing.
	 * </p>
	 */
	@Test
	void distributeSplitsPanelsIntoBalancedContiguousChunks() {
		// 4 panels over 2 frames: {0,1} then {2,3} — contiguous, not round-robin.
		assertArrayEquals(new int[] { 0, 0, 1, 1 }, DockLayout.distribute(4, 2));
		// 5 over 2: the larger chunk comes first.
		assertArrayEquals(new int[] { 0, 0, 0, 1, 1 }, DockLayout.distribute(5, 2));
		// 3 over 3: one panel per frame.
		assertArrayEquals(new int[] { 0, 1, 2 }, DockLayout.distribute(3, 3));
	}

	/**
	 * Degenerate frame counts: one, zero, and negative.
	 *
	 * <p>
	 * Zero and negative frame counts are not expected to occur — but "not expected" is
	 * not the same as "cannot". The frame count is derived from configuration and screen
	 * measurements, so an unlucky combination could produce one, and the honest question
	 * is not "will this happen" but "what does the code do if it does". Here the answer
	 * is defined: everything collapses onto frame 0, which is always a valid frame index
	 * for a non-empty result. No exception, no negative index into an array.
	 * </p>
	 *
	 * <p>
	 * Testing negative inputs to a parameter that "obviously" cannot be negative is
	 * cheap insurance. Note the alternative design — throwing
	 * {@code IllegalArgumentException} — would be equally defensible; the value of the
	 * test is that it makes the choice explicit and permanent rather than accidental.
	 * </p>
	 */
	@Test
	void distributeAssignsEverythingToFrameZeroWhenFramesIsOneOrLess() {
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, 1));
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, 0));
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, -2));
	}

	/**
	 * More frames than panels — and an example of asserting properties when the exact
	 * answer is not the thing you care about.
	 *
	 * <p>
	 * With 2 panels over 4 frames, two frames must end up empty. Which two is genuinely
	 * an implementation detail: the callers show a placeholder in any empty frame, so
	 * they do not care. Rather than freeze an arbitrary choice with
	 * {@code assertArrayEquals}, this test asserts the three things that actually
	 * matter — the result has one entry per panel, every frame index is within range,
	 * and the assignment never goes backwards (which is what "contiguous" means).
	 * </p>
	 *
	 * <p>
	 * This is the counterweight to the earlier advice about pinning down behaviour. Pin
	 * down what callers depend on; leave genuinely free choices free, or you have written
	 * a test that forbids valid refactorings. Knowing which is which is the judgement
	 * call, and getting it wrong in the strict direction produces the brittle test suites
	 * that teams eventually stop trusting.
	 * </p>
	 *
	 * <p>
	 * Both loop assertions carry a message string. Inside a loop this is close to
	 * mandatory: without one, a failure tells you an assertion in this method failed but
	 * not which iteration or which rule.
	 * </p>
	 */
	@Test
	void distributeWithMoreFramesThanPanelsLeavesSomeFramesEmpty() {
		int[] assignment = DockLayout.distribute(2, 4);
		assertEquals(2, assignment.length);
		// Frame indices stay in range and are monotonically non-decreasing.
		int prev = -1;
		for (int frame : assignment) {
			assertTrue(frame >= 0 && frame < 4, "frame index in range");
			assertTrue(frame >= prev, "contiguous assignment never goes backwards");
			prev = frame;
		}
	}

	/**
	 * The empty case — the boundary that catches more bugs than any other.
	 *
	 * <p>
	 * Zero-length input is where loops that assume at least one element, and index
	 * arithmetic like {@code count / frames} or {@code array[0]}, tend to fall over. The
	 * expected result is an empty array, not null: a method returning a collection or
	 * array should return an empty one rather than null, so callers can loop over the
	 * result unconditionally instead of null-checking at every call site.
	 * </p>
	 *
	 * <p>
	 * One line, and it is the single highest-value test in this file per character
	 * written. When you are short on time, test the empty case first.
	 * </p>
	 */
	@Test
	void distributeHandlesZeroPanels() {
		assertArrayEquals(new int[0], DockLayout.distribute(0, 3));
	}
}
