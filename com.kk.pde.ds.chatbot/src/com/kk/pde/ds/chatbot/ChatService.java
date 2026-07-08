package com.kk.pde.ds.chatbot;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.mcp.api.Json;
import com.kk.pde.ds.mcp.llm.OpenRouterAgent;

/**
 * DS component that manages chat conversations with an LLM.
 * Maintains message history and delegates to OpenRouterAgent for completions.
 * Settings are loaded from and persisted to {@code settings/chatbot.properties}
 * via {@link ChatConfig}.
 *
 * Registered as a service so other bundles (e.g. App) can inject it.
 */
@Component(service = ChatService.class)
public class ChatService {

	private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

	private OpenRouterAgent agent;
	private final List<String> history = Collections.synchronizedList(new ArrayList<String>());
	private ChatConfig config;

	@Activate
	public void activate() {
		config = new ChatConfig();
		LOG.info("ChatService activated (config loaded)");
	}

	@Reference
	public void setAgent(OpenRouterAgent agent) {
		this.agent = agent;
		LOG.info("ChatService: OpenRouterAgent injected");
	}

	/** Get the configuration object. */
	public ChatConfig getConfig() {
		return config;
	}

	/**
	 * Send a user message and get the LLM's response.
	 * The message and response are added to conversation history.
	 *
	 * @param userMessage the user's text input
	 * @return the LLM's response text
	 */
	public String send(String userMessage) {
		if (userMessage == null || userMessage.trim().isEmpty()) {
			return "(empty message)";
		}

		history.add("{\"role\":\"user\",\"content\":\""
			+ Json.escape(userMessage) + "\"}");

		LOG.info("Sending chat message (history size={}): {}",
			history.size(), userMessage);

		// Pass credentials per call rather than mutating the shared agent singleton,
		// so a Settings change here never leaks into the /llm/chat servlet's requests.
		String response = agent.chatWithHistory(history, getModel(), getApiKey(), getBaseUrl());

		LOG.info("Chat response: {}", response);
		return response;
	}

	/** Clear all conversation history. */
	public void clearHistory() {
		history.clear();
		LOG.info("Chat history cleared");
	}

	/** Get the number of messages in history. */
	public int getHistorySize() {
		return history.size();
	}

	/** Set the model ID and persist to config. */
	public void setModel(String model) {
		config.setModel(model);
		LOG.info("Chat model set to: {}", model);
	}

	/** Get the current model ID. */
	public String getModel() {
		return config.getModel();
	}

	/** Set the API base URL and persist to config. */
	public void setBaseUrl(String baseUrl) {
		config.setBaseUrl(baseUrl);
		LOG.info("Chat base URL set to: {}", baseUrl);
	}

	/** Get the API base URL. */
	public String getBaseUrl() {
		return config.getBaseUrl();
	}

	/** Get the API key (resolved via config priority chain). */
	public String getApiKey() {
		return config.getApiKey();
	}

	/** Set an API key and persist to config. */
	public void setApiKey(String apiKey) {
		config.setApiKey(apiKey);
		LOG.info("Chat API key set");
	}

	/**
	 * Fetch available models from the configured API endpoint.
	 * Runs synchronously — caller should invoke from a background thread.
	 */
	public List<String> fetchModels() {
		return ModelFetcher.fetchModels(getBaseUrl(), getApiKey());
	}
}
