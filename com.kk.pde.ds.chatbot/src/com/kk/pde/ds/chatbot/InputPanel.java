package com.kk.pde.ds.chatbot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Input panel with multi-line text area, Send button, and Clear History button.
 * Enter sends the message; Shift+Enter inserts a newline.
 */
public class InputPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Callback interface for when the user submits a message. */
	public interface SendListener {
		void onSend(String message);
	}

	/** Callback interface for when the user clears history. */
	public interface ClearListener {
		void onClear();
	}

	private final JTextArea textArea;
	private final JButton sendButton;
	private final JButton clearButton;

	public InputPanel(final SendListener sendListener, final ClearListener clearListener) {
		setLayout(new BorderLayout(5, 5));
		setBackground(DarkTheme.BG_DARK);

		textArea = new JTextArea(3, 40);
		textArea.setLineWrap(true);
		// Use character wrap instead of word wrap — word wrap scans backwards
		// on every keystroke to find word boundaries, character wrap is O(1).
		textArea.setWrapStyleWord(false);
		textArea.setBackground(DarkTheme.BG_MEDIUM);
		textArea.setForeground(DarkTheme.FG_PRIMARY);
		textArea.setCaretColor(DarkTheme.FG_PRIMARY);

		// Enter sends, Shift+Enter adds newline
		textArea.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
					e.consume();
					doSend(sendListener);
				}
			}
		});

		JScrollPane scrollPane = new JScrollPane(textArea);
		// ALWAYS show the vertical scrollbar to avoid the show/hide feedback loop:
		// AS_NEEDED checks on every document change whether the scrollbar should
		// appear, which changes viewport width, which re-triggers word-wrap, which
		// changes document height, which re-triggers the scrollbar check...
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(BorderFactory.createLineBorder(DarkTheme.BORDER, 1));
		// Fix the preferred size so the scroll pane doesn't re-layout the
		// parent panel when the text area content changes height.
		scrollPane.setPreferredSize(new Dimension(400, 70));
		scrollPane.setMinimumSize(new Dimension(200, 50));
		add(scrollPane, BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		buttonPanel.setBackground(DarkTheme.BG_DARK);

		clearButton = DarkTheme.createButton("Clear");
		clearButton.setToolTipText("Clear conversation history");
		clearButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (clearListener != null) {
					clearListener.onClear();
				}
			}
		});
		buttonPanel.add(clearButton);

		sendButton = DarkTheme.createAccentButton("Send");
		sendButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				doSend(sendListener);
			}
		});
		buttonPanel.add(sendButton);

		add(buttonPanel, BorderLayout.EAST);
	}

	private void doSend(SendListener listener) {
		if (!sendButton.isEnabled()) {
			return; // Already waiting for a response
		}
		String text = textArea.getText().trim();
		if (!text.isEmpty() && listener != null) {
			textArea.setText("");
			listener.onSend(text);
		}
	}

	/** Enable or disable the send button (e.g. while waiting for response). */
	public void setSendEnabled(boolean enabled) {
		sendButton.setEnabled(enabled);
		// Do NOT disable textArea — the user should be able to type their next
		// message while waiting for the LLM response. Disabling the text area
		// blocks all keystrokes and feels like lag.
	}

	/**
	 * Enable or disable the Clear button. Disabled while a send is in flight so the
	 * conversation history can't be cleared mid-request (which would race the worker
	 * iterating that same history list).
	 */
	public void setClearEnabled(boolean enabled) {
		clearButton.setEnabled(enabled);
	}

	/** Request focus on the text area. */
	public void focusInput() {
		textArea.requestFocusInWindow();
	}
}
