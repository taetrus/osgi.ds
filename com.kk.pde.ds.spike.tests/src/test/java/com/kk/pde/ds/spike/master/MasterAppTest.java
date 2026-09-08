package com.kk.pde.ds.spike.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.kk.pde.ds.spike.api.CatalogItem;
import com.kk.pde.ds.spike.api.ICatalogService;

/**
 * <h2>Mocking, part 2: collaborators that return values, and Swing code under test</h2>
 *
 * <p>
 * Read {@code GreetHealthCheckTest} (in {@code com.kk.pde.ds.imp.tests}) first. It
 * introduces test doubles with a collaborator whose only method returns {@code void}.
 * {@link ICatalogService} is richer: {@code listItems()} returns data that the code
 * under test then <em>displays</em>, and {@code setSelectedId(String)} is called back
 * with an argument the test will want to inspect. That unlocks the four Mockito
 * techniques this file demonstrates:
 * </p>
 * <ul>
 * <li>{@code when(...).thenReturn(...)} — stubbing a value-returning method.</li>
 * <li>{@link ArgumentCaptor} — grabbing the argument a mock was called with.</li>
 * <li>{@link InOrder} — asserting that calls happened in a particular sequence.</li>
 * <li>{@code spy(...)} — a <em>real</em> object with recording wrapped around it,
 * plus the one stubbing gotcha that spies introduce.</li>
 * </ul>
 *
 * <h3>The subject, and why the tests stop at {@code buildPanels}</h3>
 * <p>
 * {@link MasterApp} is a Swing application. Its entry point {@code start()} schedules
 * {@code buildFrames}, which opens borderless {@code JFrame} windows and asks the
 * toolkit for the screen size. Neither works without a display: on the CI machine
 * they throw {@code HeadlessException}, and on a developer's desktop they would pop
 * windows open in the middle of the build. So this fragment's {@code pom.xml} forces
 * {@code -Djava.awt.headless=true}, and the tests call the package-private
 * {@link MasterApp#buildPanels} directly. That method creates only <em>lightweight</em>
 * components ({@code JList}, {@code JLabel}, {@code JButton}…), which are pure Java and
 * run happily headless. Everything interesting about the collaborator happens there:
 * the items are read, the summary is computed, and the first item's selection is
 * pushed back to the service.
 * </p>
 * <p>
 * That visibility change is a <strong>test seam</strong> — the smallest change to the
 * production class that lets a test reach the behaviour. Seams are normal and honest;
 * what to avoid is changing behaviour for the sake of a test.
 * </p>
 *
 * <h3>Swing and the Event Dispatch Thread</h3>
 * <p>
 * Swing components must be touched on one thread, the EDT. JUnit runs tests on its own
 * thread, so every call into {@code buildPanels} is wrapped in
 * {@code SwingUtilities.invokeAndWait}, which runs the code on the EDT and blocks until
 * it finishes. That "and wait" is what lets the assertions that follow see a finished
 * result. The selection listener inside {@code buildPanels} fires synchronously, so by
 * the time {@code invokeAndWait} returns, the mock has already been called.
 * </p>
 *
 * <h3>Two routes to the same collaborator</h3>
 * <p>
 * {@code buildPanels(svc)} reads items from its <em>parameter</em>, but the selection
 * listener it installs writes through the {@code catalog} <em>field</em>, set by the
 * public {@code setCatalog} (the DS bind method). The tests therefore hand the double
 * over both ways. Spotting that a class reaches its collaborator by two paths is
 * exactly the kind of thing writing a test makes visible.
 * </p>
 */
public class MasterAppTest {

    private MasterApp app;
    private ICatalogService catalog;

    /** A fresh app and a fresh mock per test, wired the way DS would wire them. */
    @BeforeEach
    void setUp() {
        app = new MasterApp();
        catalog = mock(ICatalogService.class);
        app.setCatalog(catalog);
    }

    // ---------------------------------------------------------------------------
    // 1. Stubbing a value-returning method
    // ---------------------------------------------------------------------------

    /**
     * Before any stubbing, a mock's {@code List}-returning method returns an
     * <em>empty list</em>, not {@code null} — Mockito's defaults are chosen to keep code
     * under test from crashing on a {@code NullPointerException}. The consequence here:
     * nothing to select, so the app must not publish a selection.
     *
     * <p>
     * {@code verify(catalog, never()).setSelectedId(any())} is the negative form of
     * interaction testing: "this was never called, with any argument". {@code any()} is
     * an <em>argument matcher</em>; once one argument of a call uses a matcher, all of
     * them must (a rule that bites when a method has several parameters).
     * </p>
     */
    @Test
    void emptyCatalogPublishesNoSelection() throws Exception {
        List<MasterApp.Panel> panels = buildPanelsOnEdt(catalog);

        assertEquals(0, catalogList(panels).getModel().getSize());
        assertEquals("0 items · 0 in stock", summaryText(panels));
        verify(catalog, never()).setSelectedId(any());
    }

    /**
     * {@code when(catalog.listItems()).thenReturn(items)} — the canonical stub. It reads
     * as a sentence, and unlike the void case in {@code GreetHealthCheckTest} the
     * method has a return value, so {@code when} can wrap the call.
     *
     * <p>
     * The assertions are on <em>state</em>: what ended up in the list model and the
     * summary label. The mock supplied the data; Swing components were the output.
     * Digging a {@code JLabel} out of a panel tree is done by a small recursive helper
     * rather than by index, so a cosmetic re-nesting of panels does not break the test.
     * </p>
     */
    @Test
    void stubbedItemsFillTheCatalogAndSummary() throws Exception {
        when(catalog.listItems()).thenReturn(twoItems());

        List<MasterApp.Panel> panels = buildPanelsOnEdt(catalog);

        assertEquals(2, catalogList(panels).getModel().getSize());
        assertEquals("2 items · 7 in stock", summaryText(panels));
    }

    // ---------------------------------------------------------------------------
    // 2. Capturing an argument
    // ---------------------------------------------------------------------------

    /**
     * {@code buildPanels} selects the first item, and the selection listener pushes its
     * id to the service. An {@link ArgumentCaptor} records the argument of that call
     * so the test can assert on it afterwards.
     *
     * <p>
     * Honesty note: for a {@code String}, {@code verify(catalog).setSelectedId("p1")}
     * says the same thing in one line, and is preferable. Captors earn their keep when
     * the argument is an object you want to inspect field by field, or one without a
     * useful {@code equals}, or when a method is called several times and you want
     * {@code getAllValues()}. The pattern is shown here on a simple type so the
     * mechanics are clear before you need them on a hard one.
     * </p>
     */
    @Test
    void selectingTheFirstItemPublishesItsId() throws Exception {
        when(catalog.listItems()).thenReturn(twoItems());

        buildPanelsOnEdt(catalog);

        ArgumentCaptor<String> selectedId = ArgumentCaptor.forClass(String.class);
        verify(catalog).setSelectedId(selectedId.capture());
        assertEquals("p1", selectedId.getValue());
    }

    // ---------------------------------------------------------------------------
    // 3. Order of interactions
    // ---------------------------------------------------------------------------

    /**
     * Plain {@code verify} does not care about order. When order <em>is</em> the
     * contract — here, the items must be read before a selection among them is
     * published — {@link InOrder} enforces it. Calls not mentioned in the sequence are
     * ignored, so this is stricter than {@code verify} but looser than
     * {@code verifyNoMoreInteractions}.
     */
    @Test
    void itemsAreReadBeforeTheSelectionIsPublished() throws Exception {
        when(catalog.listItems()).thenReturn(twoItems());

        buildPanelsOnEdt(catalog);

        InOrder inOrder = inOrder(catalog);
        inOrder.verify(catalog).listItems();
        inOrder.verify(catalog).setSelectedId("p1");
    }

    // ---------------------------------------------------------------------------
    // 4. Spies: real behaviour, recorded
    // ---------------------------------------------------------------------------

    /**
     * A <strong>spy</strong> wraps a real object: unstubbed calls run the real code,
     * and every call is still recorded for {@code verify}. Here the real
     * {@link CatalogServiceImpl} supplies its five seeded items and really stores the
     * selection, while the test can still ask what was called.
     *
     * <p>
     * <strong>The spy gotcha #1: it is a copy.</strong> {@code spy(real)} does not wrap
     * {@code real} in place — it builds a new instance and copies the fields across.
     * Calls through the spy update the spy's state, not {@code real}'s. The last
     * assertion proves it: {@code real} never learned about the selection. Always keep
     * and query the spy, never the original.
     * </p>
     */
    @Test
    void spyRunsTheRealServiceAndRecordsCalls() throws Exception {
        CatalogServiceImpl real = new CatalogServiceImpl();
        real.start();                          // what DS would do: seed the catalog
        ICatalogService spied = spy(real);
        app.setCatalog(spied);

        List<MasterApp.Panel> panels = buildPanelsOnEdt(spied);

        assertEquals(5, catalogList(panels).getModel().getSize());
        verify(spied).setSelectedId("p1");
        assertEquals("p1", spied.getSelectedId());   // real state, really changed
        assertNull(real.getSelectedId());            // ...on the copy, not the original
    }

    /**
     * <strong>The spy gotcha #2: stub with {@code doReturn}, not {@code when}.</strong>
     * {@code when(spied.listItems()).thenReturn(x)} <em>calls the real
     * {@code listItems()}</em> while setting up the stub, because the argument to
     * {@code when} is evaluated first, like any Java expression. On this class that is
     * harmless; on a method that hits a database or throws, it is not.
     * {@code doReturn(x).when(spied).listItems()} never invokes the real method — the
     * same {@code do...().when(mock)} shape used for void methods in
     * {@code GreetHealthCheckTest}, now for a different reason.
     */
    @Test
    void spyCanBeStubbedWithoutCallingTheRealMethod() throws Exception {
        CatalogServiceImpl real = new CatalogServiceImpl();
        real.start();
        ICatalogService spied = spy(real);
        doReturn(twoItems()).when(spied).listItems();
        app.setCatalog(spied);

        List<MasterApp.Panel> panels = buildPanelsOnEdt(spied);

        assertEquals(2, catalogList(panels).getModel().getSize());   // the stub won
        assertEquals("2 items · 7 in stock", summaryText(panels));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static List<CatalogItem> twoItems() {
        return Arrays.asList(
                new CatalogItem("p1", "Hex Bolt M8", "Zinc-plated steel hex bolt", 5),
                new CatalogItem("p2", "Ball Bearing", "Sealed deep-groove ball bearing", 2));
    }

    /** Runs {@code buildPanels} on the EDT and hands the result back to the test thread. */
    private List<MasterApp.Panel> buildPanelsOnEdt(ICatalogService svc) throws Exception {
        AtomicReference<List<MasterApp.Panel>> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(app.buildPanels(svc)));
        return out.get();
    }

    private static MasterApp.Panel panel(List<MasterApp.Panel> panels, String name) {
        for (MasterApp.Panel p : panels) {
            if (name.equals(p.name)) return p;
        }
        return fail("no panel named " + name);
    }

    private static JList<?> catalogList(List<MasterApp.Panel> panels) {
        return findFirst(panel(panels, "CATALOG").content, JList.class);
    }

    private static String summaryText(List<MasterApp.Panel> panels) {
        return findFirst(panel(panels, "SUMMARY").content, JLabel.class).getText();
    }

    /** Depth-first search of a component tree for the first component of the given type. */
    private static <T> T findFirst(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                T found = findFirst(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
