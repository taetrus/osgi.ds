package com.kk.pde.ds.spike.master;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.spike.api.AnchorState;
import com.kk.pde.ds.spike.api.CatalogItem;
import com.kk.pde.ds.spike.api.DockBounds;
import com.kk.pde.ds.spike.api.DockLayout;
import com.kk.pde.ds.spike.api.ICatalogService;

/**
 * App-1 ("master") — anchor of the combined UI. Opens <em>N</em> borderless windows
 * (configurable via {@code -Dspike.frames}, default 2) tiled into a shared grid. The
 * app's panels (CATALOG, SUMMARY, ACTIVITY, CONTROLS) are split across those frames so
 * each window shows distinct content. Every frame is badged "APP-1 · MASTER" in teal so
 * its owning process is obvious when docked next to App-2.
 *
 * <p>
 * The master computes the {@link DockLayout} once and publishes it through
 * {@link ICatalogService}; the detail app reads it and tiles its own (odd-slot) frames
 * into the same grid. Master frames take the even slots — {@link DockLayout#masterSlot}.
 * </p>
 */
@Component
public class MasterApp {

	private static final Logger log = LoggerFactory.getLogger(MasterApp.class);

	static final int HEADER_H = 34;
	private static final int TILE_W = 400;
	private static final int TILE_H = 520 + HEADER_H;
	private static final int ORIGIN_X = 60;
	private static final int ORIGIN_Y = 80;
	private static final int DEFAULT_FRAMES = 2;
	private static final String FRAMES_PROP = "spike.frames";   // -Dspike.frames=N (must precede -jar)
	private static final String FRAMES_ENV = "SPIKE_FRAMES";    // SPIKE_FRAMES=N (works via the run scripts)

	private static final String APP_TAG = "APP-1 · MASTER";
	private static final Color ACCENT = new Color(0x2FD4A7);   // teal = App-1
	private static final Color HEADER_BG = new Color(0x16202C);
	private static final Color PANEL_HEAD = new Color(0x121B26);
	private static final Color BODY_BG = new Color(0x0E151F);
	private static final Color INK = new Color(0xE8EEF6);
	private static final Color MUTED = new Color(0x7C8A9C);

	private volatile ICatalogService catalog;
	private final List<FrameSlot> slots = new ArrayList<>();
	private JTextArea activity;
	private int beat;

	// --- anchor (M-1) state: the one frame the user can drag/minimize ---
	private JFrame anchorFrame;          // the slot-0 frame; null until built
	private DockBounds anchorHome;       // its layout-computed home bounds
	private JButton minBtn;              // minimize/restore toggle in the anchor header
	private int offsetX, offsetY;        // how far the anchor has been dragged from home
	private boolean minimized;           // whole-constellation collapsed?
	private int anchorExpandedHeight;    // remembered so restore can re-expand the anchor

	/** A built panel awaiting placement into one of the tiled frames. Package-private for tests. */
	static final class Panel {
		final String name;
		final JComponent content;
		Panel(String name, JComponent content) { this.name = name; this.content = content; }
	}

	/** A frame paired with its home (un-dragged) bounds, so followers can re-home at home+offset. */
	private static final class FrameSlot {
		final JFrame frame;
		final DockBounds home;
		final boolean anchor;
		FrameSlot(JFrame frame, DockBounds home, boolean anchor) { this.frame = frame; this.home = home; this.anchor = anchor; }
	}

	@Reference
	public void setCatalog(ICatalogService catalog) { this.catalog = catalog; }

	public void unsetCatalog(ICatalogService catalog) {
		if (this.catalog == catalog) this.catalog = null;
	}

	@Activate
	public void start() {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("MasterApp.start() — headless JVM, not opening App-1 windows");
			return;
		}
		log.info("MasterApp.start() — launching App-1 anchor windows");
		final ICatalogService svc = this.catalog;
		SwingUtilities.invokeLater(() -> buildFrames(svc));
	}

	private void buildFrames(ICatalogService svc) {
		int n = frameCount();
		DockLayout layout = computeLayout(n);
		svc.setLayout(layout);
		log.info("App-1 building {} frame(s); {}", n, layout);

		List<Panel> panels = buildPanels(svc);
		int[] frameForPanel = DockLayout.distribute(panels.size(), n);

		for (int k = 0; k < n; k++) {
			boolean isAnchor = (k == 0);              // M-1 is the draggable/minimizable anchor
			DockBounds b = layout.slotBounds(DockLayout.masterSlot(k));
			String title = "M-" + (k + 1);
			JFrame frame = newFrame(title, b);

			JPanel body = bodyPanel();
			boolean any = false;
			for (int i = 0; i < panels.size(); i++) {
				if (frameForPanel[i] == k) {
					body.add(badged(panels.get(i).name, panels.get(i).content));
					body.add(Box.createVerticalStrut(8));
					any = true;
				}
			}
			if (!any) body.add(badged("EXTRA", fixed(wrap(muted("(no panel assigned to this frame)")), 30)));

			JPanel root = new JPanel(new BorderLayout());
			root.add(makeHeader(frame, isAnchor ? title + " ⚓" : title, isAnchor, b), BorderLayout.NORTH);
			root.add(body, BorderLayout.CENTER);
			frame.setContentPane(root);
			frame.setVisible(true);
			slots.add(new FrameSlot(frame, b, isAnchor));
			if (isAnchor) { anchorFrame = frame; anchorHome = b; anchorExpandedHeight = b.getHeight(); }
			log.info("App-1 frame {}{} at slot {} → {},{} {}x{}", title, isAnchor ? " (anchor)" : "",
					DockLayout.masterSlot(k), b.getX(), b.getY(), b.getWidth(), b.getHeight());
		}
		// Publish the neutral anchor state so the detail starts aligned (offset 0,0; not minimized).
		if (svc != null) svc.setAnchor(AnchorState.HOME);

		// live heartbeat — proves App-1 is doing its own work in its own process
		new Timer(1000, e -> {
			beat++;
			if (activity != null) {
				activity.append("App-1 ▸ heartbeat #" + beat + "\n");
				activity.setCaretPosition(activity.getDocument().getLength());
			}
		}).start();
	}

	/**
	 * Frame count, in precedence order: {@code SPIKE_FRAMES} env var (the path that
	 * survives the run scripts), then the {@code -Dspike.frames} system property, then
	 * the default of {@value #DEFAULT_FRAMES}. Always at least 1.
	 */
	private int frameCount() {
		String env = System.getenv(FRAMES_ENV);
		if (env != null && !env.trim().isEmpty()) {
			try {
				return Math.max(1, Integer.parseInt(env.trim()));
			} catch (NumberFormatException e) {
				log.warn("Ignoring non-numeric {}='{}'", FRAMES_ENV, env);
			}
		}
		return Math.max(1, Integer.getInteger(FRAMES_PROP, DEFAULT_FRAMES));
	}

	/** Computes the shared grid: columns derived from the primary screen width. */
	private DockLayout computeLayout(int n) {
		int screenW = Toolkit.getDefaultToolkit().getScreenSize().width;
		int columns = Math.max(1, (screenW - ORIGIN_X) / TILE_W);
		return new DockLayout(n, TILE_W, TILE_H, ORIGIN_X, ORIGIN_Y, columns);
	}

	private JFrame newFrame(String title, DockBounds b) {
		JFrame frame = new JFrame("Combined Inventory · " + title);
		frame.setUndecorated(true);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setBounds(b.getX(), b.getY(), b.getWidth(), b.getHeight());
		return frame;
	}

	private JPanel bodyPanel() {
		JPanel body = new JPanel();
		body.setBackground(BODY_BG);
		body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		return body;
	}

	/**
	 * Builds the four app panels independently so they can be split across frames.
	 * Package-private so {@code MasterAppTest} can drive it headless: it creates only
	 * lightweight Swing components, unlike {@link #buildFrames} which needs real windows.
	 */
	List<Panel> buildPanels(ICatalogService svc) {
		List<Panel> panels = new ArrayList<>();

		// --- CATALOG (the list, drives the shared selection) ---
		DefaultListModel<CatalogItem> model = new DefaultListModel<>();
		List<CatalogItem> items = svc.listItems();
		int totalQty = 0;
		for (CatalogItem it : items) { model.addElement(it); totalQty += it.getQuantity(); }
		final JList<CatalogItem> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				CatalogItem sel = list.getSelectedValue();
				if (sel != null && catalog != null) catalog.setSelectedId(sel.getId());
			}
		});
		JScrollPane listScroll = new JScrollPane(list);
		panels.add(new Panel("CATALOG", grow(listScroll, 150)));

		// --- SUMMARY (computed in App-1) ---
		JLabel summary = new JLabel(items.size() + " items · " + totalQty + " in stock");
		summary.setForeground(INK);
		panels.add(new Panel("SUMMARY", fixed(wrap(summary), 30)));

		// --- ACTIVITY (live heartbeat, in App-1's process) ---
		activity = new JTextArea(4, 22);
		activity.setEditable(false);
		activity.setOpaque(false);
		activity.setForeground(ACCENT);
		activity.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		JScrollPane actScroll = new JScrollPane(activity);
		actScroll.setBorder(null);
		actScroll.setOpaque(false);
		actScroll.getViewport().setOpaque(false);
		panels.add(new Panel("ACTIVITY", grow(actScroll, 90)));

		// --- CONTROLS (isolation demo, tagged App-1) ---
		JButton freeze = new JButton("Freeze 5s");
		freeze.addActionListener(e -> {
			log.warn("App-1 EDT frozen for 5s — detail stays responsive");
			try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		});
		JButton crash = new JButton("Crash");
		crash.addActionListener(e -> { log.error("App-1 crashing on purpose — detail should survive"); System.exit(7); });
		JPanel ctl = new JPanel(new GridLayout(1, 2, 6, 6));
		ctl.setOpaque(false);
		ctl.add(freeze); ctl.add(crash);
		panels.add(new Panel("CONTROLS", fixed(ctl, 36)));

		if (!items.isEmpty()) list.setSelectedIndex(0);
		return panels;
	}

	/**
	 * Builds a frame's title strip. The anchor frame ({@code isAnchor}) gets a minimize
	 * toggle plus a drag handler that moves the <em>whole</em> constellation; follower
	 * frames get a locked header (no drag) so the group keeps its relative layout.
	 */
	private JPanel makeHeader(JFrame frame, String title, boolean isAnchor, DockBounds home) {
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(HEADER_BG);
		header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
		header.setPreferredSize(new Dimension(10, HEADER_H));

		JButton close = iconButton("●", new Color(0xFF5B57), "Close combined app");
		close.addActionListener(e -> { if (catalog != null) catalog.requestShutdown(); });

		JPanel controls = new JPanel();
		controls.setOpaque(false);
		controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
		controls.add(close);
		if (isAnchor) {
			minBtn = iconButton("–", MUTED, "Minimize the whole combined app");
			minBtn.addActionListener(e -> toggleMinimize());
			controls.add(Box.createHorizontalStrut(6));
			controls.add(minBtn);
		}

		JLabel label = new JLabel("▦  " + title);
		label.setForeground(INK);
		label.setHorizontalAlignment(SwingConstants.CENTER);

		header.add(controls, BorderLayout.WEST);
		header.add(label, BorderLayout.CENTER);

		if (isAnchor) {
			// Only the anchor is draggable; dragging it re-homes every follower at home+offset.
			final Point[] a = { null };
			MouseAdapter drag = new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) { a[0] = e.getPoint(); }
				@Override public void mouseReleased(MouseEvent e) { a[0] = null; }
				@Override public void mouseDragged(MouseEvent e) {
					Point p = a[0];
					if (p == null) return;
					frame.setLocation(frame.getX() + e.getX() - p.x, frame.getY() + e.getY() - p.y);
					onAnchorMoved();
				}
			};
			header.addMouseListener(drag); header.addMouseMotionListener(drag);
			label.addMouseListener(drag); label.addMouseMotionListener(drag);
			label.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
		}
		return header;
	}

	/** A flat, borderless icon button styled for the dark header. */
	private JButton iconButton(String glyph, Color fg, String tip) {
		JButton b = new JButton(glyph);
		b.setForeground(fg);
		b.setBackground(HEADER_BG);
		b.setBorder(BorderFactory.createEmptyBorder());
		b.setFocusPainted(false);
		b.setOpaque(true);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setToolTipText(tip);
		return b;
	}

	/** Anchor dragged: recompute its offset from home, push it to followers + the detail. */
	private void onAnchorMoved() {
		if (anchorFrame == null || anchorHome == null) return;
		offsetX = anchorFrame.getX() - anchorHome.getX();
		offsetY = anchorFrame.getY() - anchorHome.getY();
		repositionFollowers();
		publishAnchor();
	}

	/** Move every non-anchor master frame to its home position shifted by the current offset. */
	private void repositionFollowers() {
		for (FrameSlot s : slots) {
			if (s.anchor) continue;
			s.frame.setLocation(s.home.getX() + offsetX, s.home.getY() + offsetY);
		}
	}

	/** Publish the live anchor offset + minimize flag so the detail JVM can follow. */
	private void publishAnchor() {
		ICatalogService svc = this.catalog;
		if (svc != null) svc.setAnchor(new AnchorState(offsetX, offsetY, minimized));
	}

	/**
	 * Minimize/restore the whole combined UI. Borderless frames can't OS-iconify reliably,
	 * so minimize collapses the anchor to just its header strip (still draggable, still
	 * holding the restore button) and hides every follower; restore reverses both.
	 */
	private void toggleMinimize() {
		if (anchorFrame == null) return;
		minimized = !minimized;
		if (minimized) {
			anchorExpandedHeight = anchorFrame.getHeight();
			anchorFrame.setSize(anchorFrame.getWidth(), HEADER_H);
			for (FrameSlot s : slots) if (!s.anchor) s.frame.setVisible(false);
			minBtn.setText("□");
			minBtn.setToolTipText("Restore the combined app");
		} else {
			anchorFrame.setSize(anchorFrame.getWidth(), anchorExpandedHeight);
			repositionFollowers();
			for (FrameSlot s : slots) if (!s.anchor) s.frame.setVisible(true);
			minBtn.setText("–");
			minBtn.setToolTipText("Minimize the whole combined app");
		}
		anchorFrame.validate();
		publishAnchor();
	}

	/** Wraps content in a badged container with a teal accent + "APP-1 · MASTER" tag. */
	private JComponent badged(String name, JComponent content) {
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(BODY_BG);
		p.setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT));
		p.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(PANEL_HEAD);
		head.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
		JLabel tag = new JLabel(APP_TAG);
		tag.setForeground(ACCENT);
		tag.setFont(tag.getFont().deriveFont(Font.BOLD, 10f));
		JLabel nm = new JLabel(name);
		nm.setForeground(MUTED);
		nm.setFont(nm.getFont().deriveFont(10f));
		nm.setHorizontalAlignment(SwingConstants.RIGHT);
		head.add(tag, BorderLayout.WEST);
		head.add(nm, BorderLayout.EAST);

		content.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		content.setOpaque(false);

		p.add(head, BorderLayout.NORTH);
		p.add(content, BorderLayout.CENTER);
		return p;
	}

	private JLabel muted(String text) { JLabel l = new JLabel(text); l.setForeground(MUTED); return l; }

	private JComponent wrap(JComponent c) {
		JPanel w = new JPanel(new BorderLayout());
		w.setOpaque(false);
		w.add(c, BorderLayout.WEST);
		return w;
	}

	private JComponent grow(JComponent c, int pref) {
		c.setPreferredSize(new Dimension(360, pref));
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref + 60));
		return c;
	}

	private JComponent fixed(JComponent c, int h) {
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		c.setPreferredSize(new Dimension(360, h));
		return c;
	}
}
