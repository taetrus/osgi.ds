package com.kk.pde.ds.spike.master;

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
 */
public class DockLayoutTest {

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

	@Test
	void columnsAreClampedToAtLeastOne() {
		DockLayout layout = new DockLayout(1, 100, 100, 0, 0, 0);
		assertEquals(1, layout.getColumns());
		// With one column everything stacks vertically instead of dividing by zero.
		assertEquals(new DockBounds(0, 200, 100, 100), layout.slotBounds(2));
	}

	@Test
	void distributeSplitsPanelsIntoBalancedContiguousChunks() {
		// 4 panels over 2 frames: {0,1} then {2,3} — contiguous, not round-robin.
		assertArrayEquals(new int[] { 0, 0, 1, 1 }, DockLayout.distribute(4, 2));
		// 5 over 2: the larger chunk comes first.
		assertArrayEquals(new int[] { 0, 0, 0, 1, 1 }, DockLayout.distribute(5, 2));
		// 3 over 3: one panel per frame.
		assertArrayEquals(new int[] { 0, 1, 2 }, DockLayout.distribute(3, 3));
	}

	@Test
	void distributeAssignsEverythingToFrameZeroWhenFramesIsOneOrLess() {
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, 1));
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, 0));
		assertArrayEquals(new int[] { 0, 0, 0 }, DockLayout.distribute(3, -2));
	}

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

	@Test
	void distributeHandlesZeroPanels() {
		assertArrayEquals(new int[0], DockLayout.distribute(0, 3));
	}
}
