package com.kk.pde.ds.mcp.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.mcp.api.IMcpTool;
import com.kk.pde.ds.mcp.api.IMcpToolRegistry;
import com.kk.pde.ds.mcp.api.Json;

/**
 * Bridges the OSGi MCP tool registry to the OpenRouter API (OpenAI-compatible).
 *
 * Reads available tools directly from IMcpToolRegistry via OSGi @Reference —
 * no HTTP call to the MCP server needed; tool execution happens in-process.
 *
 * Agent loop:
 *  1. Sends user message + tools to OpenRouter chat completions
 *  2. If model responds with tool_calls: executes the tool via the registry,
 *     appends the result to the conversation, and repeats
 *  3. If model responds with stop: returns the final text answer
 *
 * Configuration via system properties (set with -D at startup):
 *   openrouter.api.key   — required; your OpenRouter API key
 *   openrouter.model     — optional; model ID (default: google/gemini-flash-1.5)
 *   openrouter.base.url  — optional; API base URL (default: https://openrouter.ai/api/v1)
 */
@Component(service = OpenRouterAgent.class)
public class OpenRouterAgent {

	private static final Logger LOG = LoggerFactory.getLogger(OpenRouterAgent.class);
	private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
	private static final String DEFAULT_MODEL = "google/gemini-flash-1.5";
	private static final int MAX_TURNS = 10;

	/** Name of the RAG retrieval tool whose results carry document citations. */
	private static final String DOC_SEARCH_TOOL = "document_search";

	/** A citation line emitted by document_search, e.g. "[1] report.pdf (chunk 2)  (score 0.812)". */
	private static final Pattern CITATION_LINE = Pattern.compile("^\\s*\\[\\d+\\]\\s+(.*)$");

	private IMcpToolRegistry registry;

	@Activate
	public void activate() {
		LOG.info("OpenRouterAgent activated");
	}

	@Reference
	public void setRegistry(IMcpToolRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Run an agent conversation using the ambient credentials
	 * ({@code -Dopenrouter.*} / {@code OPENROUTER_API_KEY}). Tools are auto-discovered
	 * from the OSGi registry.
	 */
	public String chat(String userMessage, String modelOverride) {
		return chat(userMessage, modelOverride, null, null);
	}

	/**
	 * Run an agent conversation with an explicit key and base URL (e.g. from the
	 * Swing chatbot's saved settings). Passing them as arguments — rather than
	 * mutating shared instance state — keeps concurrent callers (the Swing UI and the
	 * {@code /llm/chat} servlet, which share this DS singleton) isolated from one another.
	 *
	 * @param apiKey   key to use, or null/empty to fall back to system property / env var
	 * @param baseUrl  base URL to use, or null/empty to fall back to system property / default
	 */
	public String chat(String userMessage, String modelOverride, String apiKey, String baseUrl) {
		String model = (modelOverride != null && !modelOverride.isEmpty())
			? modelOverride
			: System.getProperty("openrouter.model", DEFAULT_MODEL);

		List<String> messages = new ArrayList<String>();
		messages.add(Json.stringify(userMessageJson(userMessage)));

		return chatWithHistory(messages, model, apiKey, baseUrl);
	}

	/**
	 * Run an agent conversation with externally-managed history and ambient credentials.
	 */
	public String chatWithHistory(List<String> messages, String model) {
		return chatWithHistory(messages, model, null, null);
	}

	/**
	 * Run an agent conversation with externally-managed message history and explicit
	 * credentials. The caller provides the full message list (including prior
	 * user/assistant messages); new messages from tool-call loops are appended to it.
	 *
	 * @param messages     mutable list of JSON message strings (caller retains reference)
	 * @param model        model ID to use
	 * @param apiKeyOverride key to use, or null/empty to fall back to system property / env var
	 * @param baseUrlOverride base URL to use, or null/empty to fall back to system property / default
	 * @return final text answer from the LLM, or error string
	 */
	public String chatWithHistory(List<String> messages, String model,
			String apiKeyOverride, String baseUrlOverride) {
		String apiKey = resolveApiKey(apiKeyOverride);
		if (apiKey.isEmpty()) {
			return "Error: No API key found. Set OPENROUTER_API_KEY env var "
				+ "or start with -Dopenrouter.api.key=your_key";
		}
		String baseUrl = resolveBaseUrl(baseUrlOverride);

		if (model == null || model.isEmpty()) {
			model = System.getProperty("openrouter.model", DEFAULT_MODEL);
		}

		String toolsJson = buildToolsJson();
		LOG.info("Chat with history: model={}, messages={}, tools={}",
			model, messages.size(), registry.getTools().size());

		// Documents cited via document_search during this answer (insertion-ordered, deduped).
		Set<String> referencedDocs = new LinkedHashSet<String>();

		for (int turn = 0; turn < MAX_TURNS; turn++) {
			String requestBody = buildRequest(model, messages, toolsJson);
			String response = post(apiKey, baseUrl, requestBody);

			if (response == null) {
				return "Error: failed to reach OpenRouter API";
			}

			LOG.debug("OpenRouter response (turn {}): {}", turn, response);

			Object root;
			try {
				root = Json.parse(response);
			} catch (Json.JsonException e) {
				LOG.error("Failed to parse OpenRouter response: {}", e.getMessage());
				return "Error: unexpected response format from OpenRouter";
			}

			List<Object> choices = Json.asList(Json.get(root, "choices"));
			if (choices == null || choices.isEmpty()) {
				Object errorBlock = Json.get(root, "error");
				if (errorBlock != null) {
					return "Error from OpenRouter: " + Json.getString(errorBlock, "message");
				}
				return "Error: unexpected response format from OpenRouter";
			}

			Object firstChoice = choices.get(0);
			String finishReason = Json.getString(firstChoice, "finish_reason");
			Object message = Json.get(firstChoice, "message");

			if ("stop".equals(finishReason) || finishReason == null) {
				String content = Json.getString(message, "content");
				if (content != null) {
					messages.add(Json.stringify(assistantMessage(content)));
				}
				String answer = content != null ? content : "(no content in response)";
				return appendReferences(answer, referencedDocs);
			}

			if ("tool_calls".equals(finishReason)) {
				List<Object> toolCalls = Json.asList(Json.get(message, "tool_calls"));
				if (toolCalls == null || toolCalls.isEmpty()) {
					return "Error: tool_calls array is empty";
				}

				// One assistant message must echo ALL tool calls, followed by one tool
				// result per call — appending only the first breaks strict providers.
				messages.add(buildAssistantToolCallsMessage(toolCalls));

				for (Object call : toolCalls) {
					String callId = Json.getString(call, "id");
					Object function = Json.get(call, "function");
					String toolName = Json.getString(function, "name");
					Map<String, String> args = parseArguments(function);

					LOG.info("Tool call: {} args={}", toolName, Json.stringify(args));

					String toolResult = executeTool(toolName, args);
					LOG.info("Tool result for {}: {}", toolName, toolResult);

					if (DOC_SEARCH_TOOL.equals(toolName)) {
						collectDocumentReferences(toolResult, referencedDocs);
					}

					messages.add(Json.stringify(toolMessage(callId, toolResult)));
				}
			} else {
				LOG.warn("Unexpected finish_reason: {}", finishReason);
				break;
			}
		}

		return "Error: maximum turns (" + MAX_TURNS + ") reached without a final answer";
	}

	private String executeTool(String toolName, Map<String, String> args) {
		IMcpTool tool = registry.getTool(toolName);
		if (tool == null) {
			return "Error: unknown tool '" + toolName + "'";
		}
		try {
			return tool.execute(args);
		} catch (Exception e) {
			LOG.error("Tool execution failed: {}", toolName, e);
			return "Error executing tool '" + toolName + "': " + e.getMessage();
		}
	}

	/**
	 * Pull source document names out of a document_search result and add them to
	 * {@code out} (deduped, insertion-ordered). The tool emits one citation line per
	 * hit: {@code "[1] report.pdf (chunk 2)  (score 0.812)"}. We strip the
	 * {@code "[n] "} prefix, then strip the trailing {@code "  (score …)"} and the
	 * trailing {@code " (location)"} group, leaving the source filename.
	 *
	 * <p>Coupled to {@code DocumentSearchTool.execute}'s output format. The stripping
	 * is deliberately tolerant (it removes trailing parenthesised groups rather than
	 * matching "chunk"/"score" literals), so wording tweaks there won't break it. If
	 * that citation line is ever redesigned, update this method.</p>
	 */
	private void collectDocumentReferences(String toolResult, Set<String> out) {
		if (toolResult == null || toolResult.isEmpty()) {
			return;
		}
		for (String line : toolResult.split("\n")) {
			Matcher m = CITATION_LINE.matcher(line);
			if (!m.matches()) {
				continue;
			}
			String rest = m.group(1).trim();                 // "report.pdf (chunk 2)  (score 0.812)"
			rest = rest.replaceFirst("\\s*\\([^()]*\\)\\s*$", ""); // drop "  (score 0.812)"
			rest = rest.replaceFirst("\\s*\\([^()]*\\)\\s*$", ""); // drop " (chunk 2)"
			String source = rest.trim();
			if (!source.isEmpty()) {
				out.add(source);
			}
		}
	}

	/**
	 * Append a References footer to the answer. Lists the cited document names, or
	 * "none" when the answer used no documents. Added to the returned text only —
	 * never to the conversation history.
	 */
	private String appendReferences(String answer, Set<String> docs) {
		StringBuilder sb = new StringBuilder(answer == null ? "" : answer);
		sb.append("\n\n---\n**References:** ");
		if (docs.isEmpty()) {
			sb.append("none");
		} else {
			sb.append("\n");
			for (String doc : docs) {
				sb.append("- ").append(doc).append("\n");
			}
		}
		return sb.toString();
	}

	private String buildToolsJson() {
		StringBuilder sb = new StringBuilder("[");
		List<IMcpTool> tools = registry.getTools();
		for (int i = 0; i < tools.size(); i++) {
			if (i > 0) sb.append(",");
			IMcpTool tool = tools.get(i);
			sb.append("{\"type\":\"function\",\"function\":{");
			sb.append("\"name\":\"").append(Json.escape(tool.getName())).append("\",");
			sb.append("\"description\":\"").append(Json.escape(tool.getDescription())).append("\",");
			sb.append("\"parameters\":").append(tool.getInputSchema());
			sb.append("}}");
		}
		sb.append("]");
		return sb.toString();
	}

	private String buildRequest(String model, List<String> messages, String toolsJson) {
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"model\":\"").append(Json.escape(model)).append("\",");
		sb.append("\"messages\":[");
		for (int i = 0; i < messages.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(messages.get(i));
		}
		sb.append("],");
		sb.append("\"tools\":").append(toolsJson).append(",");
		sb.append("\"tool_choice\":\"auto\"");
		sb.append("}");
		return sb.toString();
	}

	/**
	 * Extract tool-call arguments into a flat string map for {@link IMcpTool#execute},
	 * handling both formats models emit: {@code "arguments":"{...}"} (a JSON string, the
	 * OpenAI standard) and {@code "arguments":{...}} (a JSON object, which several
	 * OpenRouter models produce). Scalars become their textual form; nested values are
	 * re-serialized to JSON so a tool that opts into structured input still gets it.
	 */
	private Map<String, String> parseArguments(Object function) {
		Map<String, Object> obj = argumentsObject(function);
		Map<String, String> flat = new LinkedHashMap<String, String>();
		for (Map.Entry<String, Object> e : obj.entrySet()) {
			Object v = e.getValue();
			if (v instanceof Map || v instanceof List) {
				flat.put(e.getKey(), Json.stringify(v));
			} else {
				flat.put(e.getKey(), Json.asString(v));
			}
		}
		return flat;
	}

	/** Normalize a function's {@code arguments} (string- or object-form) to a map. */
	private Map<String, Object> argumentsObject(Object function) {
		Object args = Json.get(function, "arguments");
		if (args instanceof Map) {
			return Json.asObject(args);
		}
		if (args instanceof String) {
			String s = ((String) args).trim();
			if (!s.isEmpty()) {
				try {
					Map<String, Object> parsed = Json.asObject(Json.parse(s));
					if (parsed != null) {
						return parsed;
					}
				} catch (Json.JsonException ignored) {
					// malformed arguments — fall through to empty
				}
			}
		}
		return new LinkedHashMap<String, Object>();
	}

	/**
	 * Build the assistant message that echoes every tool call the model requested.
	 * Arguments are re-serialized to a JSON string, the format the OpenAI/OpenRouter
	 * protocol expects on the assistant turn.
	 */
	private String buildAssistantToolCallsMessage(List<Object> toolCalls) {
		List<Object> normalized = new ArrayList<Object>();
		for (Object call : toolCalls) {
			Map<String, Object> function = new LinkedHashMap<String, Object>();
			function.put("name", Json.getString(call, "name") != null
				? Json.getString(call, "name")
				: Json.getString(Json.get(call, "function"), "name"));
			function.put("arguments", Json.stringify(argumentsObject(Json.get(call, "function"))));

			Map<String, Object> entry = new LinkedHashMap<String, Object>();
			entry.put("id", Json.getString(call, "id"));
			entry.put("type", "function");
			entry.put("function", function);
			normalized.add(entry);
		}
		Map<String, Object> msg = new LinkedHashMap<String, Object>();
		msg.put("role", "assistant");
		msg.put("content", null);
		msg.put("tool_calls", normalized);
		return Json.stringify(msg);
	}

	private Map<String, Object> userMessageJson(String content) {
		Map<String, Object> msg = new LinkedHashMap<String, Object>();
		msg.put("role", "user");
		msg.put("content", content);
		return msg;
	}

	private Map<String, Object> assistantMessage(String content) {
		Map<String, Object> msg = new LinkedHashMap<String, Object>();
		msg.put("role", "assistant");
		msg.put("content", content);
		return msg;
	}

	private Map<String, Object> toolMessage(String callId, String content) {
		Map<String, Object> msg = new LinkedHashMap<String, Object>();
		msg.put("role", "tool");
		msg.put("tool_call_id", callId);
		msg.put("content", content);
		return msg;
	}

	/**
	 * Resolve the API key. Priority: explicit override &rarr; {@code -Dopenrouter.api.key}
	 * &rarr; {@code OPENROUTER_API_KEY} env var.
	 */
	private String resolveApiKey(String override) {
		if (override != null && !override.isEmpty()) {
			return override;
		}
		String key = System.getProperty("openrouter.api.key", "");
		if (key.isEmpty()) {
			String envKey = System.getenv("OPENROUTER_API_KEY");
			if (envKey != null && !envKey.isEmpty()) {
				key = envKey;
			}
		}
		return key;
	}

	/**
	 * Resolve the API base URL. Priority: explicit override (e.g. the Swing chatbot's
	 * saved setting) &rarr; {@code -Dopenrouter.base.url} system property &rarr; built-in
	 * default. Mirrors the embeddings client and ChatConfig. A trailing slash is tolerated.
	 */
	private String resolveBaseUrl(String override) {
		String base = (override != null && !override.isEmpty())
			? override
			: System.getProperty("openrouter.base.url", DEFAULT_BASE_URL);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private String post(String apiKey, String baseUrl, String jsonBody) {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(baseUrl + "/chat/completions");
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setRequestProperty("Authorization", "Bearer " + apiKey);
			conn.setRequestProperty("HTTP-Referer", "http://localhost:8080");
			conn.setRequestProperty("X-Title", "OSGi MCP Bridge");
			conn.setDoOutput(true);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(60000);

			OutputStream os = conn.getOutputStream();
			os.write(jsonBody.getBytes("UTF-8"));
			os.flush();
			os.close();

			int status = conn.getResponseCode();
			java.io.InputStream stream = (status >= 200 && status < 300)
				? conn.getInputStream() : conn.getErrorStream();
			if (stream == null) {
				// No response body (e.g. some 4xx / proxy failures) — getErrorStream()
				// returns null here; avoid the NPE that would escape the IOException catch.
				LOG.error("OpenRouter returned HTTP {} with no body", status);
				return null;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) sb.append(line);
			reader.close();
			return sb.toString();

		} catch (IOException e) {
			LOG.error("HTTP POST to OpenRouter failed: {}", e.getMessage());
			return null;
		} finally {
			if (conn != null) conn.disconnect();
		}
	}
}
