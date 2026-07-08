package com.kk.pde.ds.chatbot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main chat window. Contains a model selector bar at the top,
 * a scrollable chat display in the center, and an input panel at the bottom.
 *
 * On startup, tests the API connection and auto-refreshes the model list.
 * The model combo supports type-to-filter autocomplete.
 */
public class ChatFrame extends JFrame {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(ChatFrame.class);

	private final ChatService chatService;
	private final ChatPanel chatPanel;
	private final InputPanel inputPanel;
	private final JComboBox<String> modelCombo;
	private final DefaultComboBoxModel<String> modelComboModel;
	private final JButton refreshModelsButton;
	private final JButton setDefaultButton;
	private boolean suppressModelAction;

	/** Full unfiltered model list (populated after fetch). */
	private List<String> allModels = new ArrayList<String>();

	public ChatFrame(ChatService chatService) {
		this.chatService = chatService;

		setTitle("OSGi Chatbot");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		setPreferredSize(new Dimension(900, 600));

		JPanel contentPane = new JPanel(new BorderLayout(5, 5));
		contentPane.setBorder(new EmptyBorder(8, 8, 8, 8));
		contentPane.setBackground(DarkTheme.BG_DARK);
		setContentPane(contentPane);

		// --- Top bar: model selector ---
		JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
		topBar.setBackground(DarkTheme.BG_DARK);
		topBar.setOpaque(true);
		JLabel modelLabel = new JLabel("Model:");
		modelLabel.setForeground(DarkTheme.FG_PRIMARY);
		topBar.add(modelLabel);

		modelComboModel = new DefaultComboBoxModel<String>();
		String defaultModel = chatService.getModel();
		modelComboModel.addElement(defaultModel);
		modelCombo = new JComboBox<String>(modelComboModel);
		modelCombo.setEditable(true);
		modelCombo.setPreferredSize(new Dimension(350, 26));
		modelCombo.setBackground(DarkTheme.BG_MEDIUM);
		modelCombo.setForeground(DarkTheme.FG_PRIMARY);
		modelCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (suppressModelAction) return;
				String selected = (String) modelCombo.getSelectedItem();
				if (selected != null && !selected.isEmpty()
						&& allModels.contains(selected)) {
					chatService.setModel(selected);
					// Update editor text and restore full list
					suppressModelAction = true;
					try {
						modelComboModel.removeAllElements();
						for (String m : allModels) {
							modelComboModel.addElement(m);
						}
						modelCombo.setSelectedItem(selected);
					} finally {
						suppressModelAction = false;
					}
				}
			}
		});
		setupAutocomplete();
		topBar.add(modelCombo);

		refreshModelsButton = DarkTheme.createButton("Refresh Models");
		refreshModelsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				fetchModelsAsync();
			}
		});
		topBar.add(refreshModelsButton);

		setDefaultButton = DarkTheme.createButton("Set Default");
		setDefaultButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				saveSelectedAsDefault();
			}
		});
		topBar.add(setDefaultButton);

		JButton settingsButton = DarkTheme.createButton("Settings");
		settingsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				new SettingsDialog(ChatFrame.this, chatService).setVisible(true);
			}
		});
		topBar.add(settingsButton);

		contentPane.add(topBar, BorderLayout.NORTH);

		// --- Center: chat display ---
		chatPanel = new ChatPanel();
		contentPane.add(chatPanel, BorderLayout.CENTER);

		// --- Bottom: input panel ---
		inputPanel = new InputPanel(
			new InputPanel.SendListener() {
				@Override
				public void onSend(String message) {
					sendMessageAsync(message);
				}
			},
			new InputPanel.ClearListener() {
				@Override
				public void onClear() {
					chatService.clearHistory();
					chatPanel.clear();
					chatPanel.addSystemMessage("Conversation cleared.");
				}
			}
		);
		contentPane.add(inputPanel, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(null);
		inputPanel.focusInput();

		// Auto-test connection and fetch models after the frame is visible
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				testConnectionAndFetchModels();
			}
		});
	}

	/**
	 * Attach a KeyListener to the combo's editor field for type-to-filter
	 * autocomplete. Uses KeyListener instead of DocumentListener so that
	 * only real keyboard input triggers filtering — programmatic text
	 * changes (e.g. when user selects from dropdown) are ignored.
	 */
	private void setupAutocomplete() {
		final JTextField editorField = (JTextField) modelCombo.getEditor().getEditorComponent();
		editorField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				int code = e.getKeyCode();
				// Ignore navigation keys — let the combo handle them
				if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE
						|| code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN) {
					return;
				}
				if (allModels.isEmpty()) return;

				String input = editorField.getText().trim().toLowerCase();
				suppressModelAction = true;
				try {
					modelComboModel.removeAllElements();
					if (input.isEmpty()) {
						for (String m : allModels) {
							modelComboModel.addElement(m);
						}
					} else {
						for (String m : allModels) {
							if (m.toLowerCase().contains(input)) {
								modelComboModel.addElement(m);
							}
						}
					}
					// Restore the typed text (addElement changes it)
					editorField.setText(input);
					if (modelComboModel.getSize() > 0) {
						modelCombo.showPopup();
					} else {
						modelCombo.hidePopup();
					}
				} finally {
					suppressModelAction = false;
				}
			}
		});
	}

	/** Save the currently selected model as the default in config. */
	private void saveSelectedAsDefault() {
		String selected = (String) modelCombo.getSelectedItem();
		if (selected != null && !selected.isEmpty()) {
			chatService.setModel(selected);
			chatService.getConfig().saveAsync();
			chatPanel.addSystemMessage("Default model set to: " + selected);
		}
	}

	/**
	 * Test the API connection at startup. If successful, auto-refresh
	 * the model list so the user can pick from available models immediately.
	 */
	private void testConnectionAndFetchModels() {
		chatPanel.addSystemMessage("Testing connection to " + chatService.getBaseUrl() + "...");

		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() {
				return chatService.fetchModels();
			}

			@Override
			protected void done() {
				try {
					List<String> models = get();
					if (models.isEmpty()) {
						chatPanel.addSystemMessage(
							"Connection failed \u2014 no models returned. "
							+ "Check API key and base URL in Settings.");
					} else {
						chatPanel.addSystemMessage(
							"Connected! Loaded " + models.size() + " tool-capable models.");
						populateModels(models);
					}
				} catch (Exception e) {
					LOG.error("Startup connection test failed", e);
					chatPanel.addSystemMessage(
						"Connection failed: " + e.getMessage()
						+ "\nConfigure API key in Settings.");
				}
			}
		}.execute();
	}

	private void sendMessageAsync(final String message) {
		chatPanel.addUserMessage(message);
		inputPanel.setSendEnabled(false);
		// Disable Clear too: clearHistory() must not run while the worker below is
		// iterating/appending the same history list.
		inputPanel.setClearEnabled(false);
		chatPanel.addSystemMessage("Thinking...");

		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return chatService.send(message);
			}

			@Override
			protected void done() {
				try {
					String response = get();
					chatPanel.addAssistantMessage(response);
				} catch (Exception e) {
					LOG.error("Chat error", e);
					chatPanel.addSystemMessage("Error: " + e.getMessage());
				} finally {
					inputPanel.setSendEnabled(true);
					inputPanel.setClearEnabled(true);
					inputPanel.focusInput();
				}
			}
		}.execute();
	}

	private void fetchModelsAsync() {
		refreshModelsButton.setEnabled(false);
		refreshModelsButton.setText("Loading...");
		chatPanel.addSystemMessage("Fetching tool-capable models from " + chatService.getBaseUrl() + "...");

		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() {
				return chatService.fetchModels();
			}

			@Override
			protected void done() {
				try {
					List<String> models = get();
					if (models.isEmpty()) {
						chatPanel.addSystemMessage("No models returned. Check API key and base URL.");
					} else {
						populateModels(models);
						chatPanel.addSystemMessage("Loaded " + models.size() + " tool-capable models.");
					}
				} catch (Exception e) {
					LOG.error("Failed to fetch models", e);
					chatPanel.addSystemMessage("Error fetching models: " + e.getMessage());
				} finally {
					refreshModelsButton.setEnabled(true);
					refreshModelsButton.setText("Refresh Models");
				}
			}
		}.execute();
	}

	/** Update the combo model and the autocomplete backing list. */
	private void populateModels(List<String> models) {
		allModels = new ArrayList<String>(models);
		String currentSelection = (String) modelCombo.getSelectedItem();
		suppressModelAction = true;
		try {
			modelComboModel.removeAllElements();
			for (String model : models) {
				modelComboModel.addElement(model);
			}
		} finally {
			suppressModelAction = false;
		}
		if (currentSelection != null) {
			modelCombo.setSelectedItem(currentSelection);
		}
	}
}
