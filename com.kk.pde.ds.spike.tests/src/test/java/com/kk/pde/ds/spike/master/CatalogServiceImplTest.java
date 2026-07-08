package com.kk.pde.ds.spike.master;

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
 */
public class CatalogServiceImplTest {

	private CatalogServiceImpl service;

	@BeforeEach
	void activate() {
		service = new CatalogServiceImpl();
		service.start();
	}

	@Test
	void activationSeedsTheCatalog() {
		List<CatalogItem> items = service.listItems();
		assertEquals(5, items.size());
		assertEquals("p1", items.get(0).getId());
	}

	@Test
	void listItemsReturnsADefensiveCopy() {
		service.listItems().clear();
		assertEquals(5, service.listItems().size(), "mutating the returned list must not touch service state");
	}

	@Test
	void getItemFindsById() {
		CatalogItem item = service.getItem("p3");
		assertEquals("O-Ring 12mm", item.getName());
	}

	@Test
	void getItemReturnsNullForUnknownId() {
		assertNull(service.getItem("nope"));
	}

	@Test
	void selectionStartsEmpty() {
		assertNull(service.getSelectedId());
		assertNull(service.getSelectedItem());
	}

	@Test
	void selectionRoundTrips() {
		service.setSelectedId("p2");
		assertEquals("p2", service.getSelectedId());
		assertEquals("Ball Bearing", service.getSelectedItem().getName());
	}

	@Test
	void selectingAnUnknownIdYieldsNoSelectedItem() {
		service.setSelectedId("ghost");
		assertEquals("ghost", service.getSelectedId());
		assertNull(service.getSelectedItem());
	}

	@Test
	void dockStateBundlesTheFullSnapshot() {
		DockLayout layout = new DockLayout(2, 300, 200, 10, 20, 4);
		service.setLayout(layout);
		service.setSelectedId("p5");
		service.setClosing(true);
		service.setAnchor(new AnchorState(42, -7, true));

		DockState state = service.getDockState();
		assertSame(layout, state.getLayout());
		assertTrue(state.isClosing());
		assertEquals("p5", state.getSelected().getId());
		assertEquals(new AnchorState(42, -7, true), state.getAnchor());
	}

	@Test
	void dockStateBeforeAnyPublishIsTheNeutralDefault() {
		DockState state = service.getDockState();
		assertNull(state.getLayout(), "layout is null until the master publishes the grid");
		assertFalse(state.isClosing());
		assertNull(state.getSelected());
		assertEquals(AnchorState.HOME, state.getAnchor());
	}

	@Test
	void nullAnchorFallsBackToHome() {
		service.setAnchor(new AnchorState(5, 5, false));
		service.setAnchor(null);
		assertEquals(AnchorState.HOME, service.getDockState().getAnchor());
	}
}
