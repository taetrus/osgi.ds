package com.kk.pde.ds.spike.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 */
public class SpikeValueObjectsTest {

	@Test
	void anchorStateEqualityAndHome() {
		assertEquals(new AnchorState(3, 4, true), new AnchorState(3, 4, true));
		assertEquals(new AnchorState(3, 4, true).hashCode(), new AnchorState(3, 4, true).hashCode());
		assertNotEquals(new AnchorState(3, 4, true), new AnchorState(3, 4, false));
		assertNotEquals(new AnchorState(3, 4, true), new AnchorState(4, 3, true));

		assertEquals(0, AnchorState.HOME.getOffsetX());
		assertEquals(0, AnchorState.HOME.getOffsetY());
		assertFalse(AnchorState.HOME.isMinimized());
	}

	@Test
	void dockBoundsEquality() {
		assertEquals(new DockBounds(1, 2, 3, 4), new DockBounds(1, 2, 3, 4));
		assertEquals(new DockBounds(1, 2, 3, 4).hashCode(), new DockBounds(1, 2, 3, 4).hashCode());
		assertNotEquals(new DockBounds(1, 2, 3, 4), new DockBounds(1, 2, 4, 3));
	}

	@Test
	void dockStateNormalizesNullAnchorToHome() {
		DockState state = new DockState(null, false, null, null);
		assertEquals(AnchorState.HOME, state.getAnchor());
	}

	@Test
	void valueObjectsSurviveSerializationRoundTrip() throws Exception {
		DockLayout layout = new DockLayout(2, 320, 240, 15, 25, 3);
		CatalogItem item = new CatalogItem("p9", "Widget", "A test widget", 7);
		DockState original = new DockState(layout, true, item, new AnchorState(10, -20, true));

		DockState copy = roundTrip(original);

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

	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(T obj) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
			oos.writeObject(obj);
		}
		try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
			return (T) ois.readObject();
		}
	}
}
