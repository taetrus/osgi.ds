package com.kk.pde.ds.mcp.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.mcp.api.IMcpTool;
import com.kk.pde.ds.mcp.api.IMcpToolRegistry;
import com.kk.pde.ds.mcp.api.Json;

/**
 * MCP (Model Context Protocol) server servlet.
 * Handles JSON-RPC 2.0 requests over HTTP at /mcp.
 *
 * Supported methods:
 * - initialize: Protocol handshake
 * - notifications/initialized: Acknowledgement (no response)
 * - tools/list: List available tools with JSON Schema
 * - tools/call: Execute a tool by name
 */
@Component(
	service = Servlet.class,
	property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/mcp",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=McpServlet"
	}
)
public class McpServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(McpServlet.class);

	private static final String PROTOCOL_VERSION = "2024-11-05";
	private static final String SERVER_NAME = "osgi-mcp-server";
	private static final String SERVER_VERSION = "1.0.0";

	/** Cap the request body so a huge POST cannot exhaust heap on the shared Jetty. */
	private static final int MAX_BODY_BYTES = 1024 * 1024;

	private IMcpToolRegistry registry;

	@Activate
	public void activate() {
		LOG.info("McpServlet activated at /mcp");
	}

	@Reference
	public void setRegistry(IMcpToolRegistry registry) {
		this.registry = registry;
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		String body = readBody(req);
		if (body == null) {
			resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			sendJson(resp, Json.stringify(errorResponse(null, -32600, "Request too large")));
			return;
		}
		LOG.debug("MCP request: {}", body);

		Map<String, Object> request;
		try {
			Object parsed = Json.parse(body);
			request = Json.asObject(parsed);
			if (request == null) {
				sendJson(resp, Json.stringify(errorResponse(null, -32600, "Invalid request: not a JSON object")));
				return;
			}
		} catch (Json.JsonException e) {
			sendJson(resp, Json.stringify(errorResponse(null, -32700, "Parse error: " + e.getMessage())));
			return;
		}

		// Preserve the id's original JSON type (string vs number vs null) for the reply.
		Object id = request.get("id");
		String method = Json.getString(request, "method");

		if (method == null) {
			sendJson(resp, Json.stringify(errorResponse(id, -32600, "Invalid request: missing method")));
			return;
		}

		Object result;
		if ("initialize".equals(method)) {
			result = successResponse(id, initializeResult());
		} else if ("notifications/initialized".equals(method)) {
			resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		} else if ("tools/list".equals(method)) {
			result = successResponse(id, toolsListResult());
		} else if ("tools/call".equals(method)) {
			result = handleToolsCall(id, request);
		} else {
			result = errorResponse(id, -32601, "Method not found: " + method);
		}

		String json = Json.stringify(result);
		LOG.debug("MCP response: {}", json);
		sendJson(resp, json);
	}

	private Map<String, Object> initializeResult() {
		LOG.info("MCP initialize request received");
		Map<String, Object> serverInfo = new LinkedHashMap<String, Object>();
		serverInfo.put("name", SERVER_NAME);
		serverInfo.put("version", SERVER_VERSION);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("protocolVersion", PROTOCOL_VERSION);
		result.put("capabilities", singletonMap("tools", new LinkedHashMap<String, Object>()));
		result.put("serverInfo", serverInfo);
		return result;
	}

	private Map<String, Object> toolsListResult() {
		List<IMcpTool> tools = registry.getTools();
		LOG.info("MCP tools/list: {} tools available", tools.size());

		List<Object> toolsJson = new ArrayList<Object>();
		for (IMcpTool tool : tools) {
			Map<String, Object> t = new LinkedHashMap<String, Object>();
			t.put("name", tool.getName());
			t.put("description", tool.getDescription());
			// inputSchema is already a JSON string — splice it in verbatim.
			t.put("inputSchema", new Json.Raw(tool.getInputSchema()));
			toolsJson.add(t);
		}
		return singletonMap("tools", toolsJson);
	}

	private Map<String, Object> handleToolsCall(Object id, Map<String, Object> request) {
		Map<String, Object> params = Json.asObject(request.get("params"));
		String toolName = params == null ? null : Json.getString(params, "name");

		if (toolName == null) {
			return errorResponse(id, -32602, "Missing tool name in params");
		}

		IMcpTool tool = registry.getTool(toolName);
		if (tool == null) {
			return errorResponse(id, -32602, "Unknown tool: " + toolName);
		}

		LOG.info("MCP tools/call: executing '{}'", toolName);

		Map<String, String> arguments = flattenArguments(Json.asObject(params.get("arguments")));

		try {
			String resultText = tool.execute(arguments);
			return successResponse(id, toolContent(resultText, false));
		} catch (Exception e) {
			LOG.error("Tool execution failed: {}", toolName, e);
			return successResponse(id, toolContent("Error: " + e.getMessage(), true));
		}
	}

	/**
	 * Convert parsed JSON arguments to the flat string map {@link IMcpTool#execute} expects.
	 * Scalars become their textual form; nested objects/arrays are re-serialized to JSON so
	 * a tool that opts into structured values still receives them intact.
	 */
	private Map<String, String> flattenArguments(Map<String, Object> args) {
		Map<String, String> flat = new LinkedHashMap<String, String>();
		if (args == null) {
			return flat;
		}
		for (Map.Entry<String, Object> e : args.entrySet()) {
			Object v = e.getValue();
			if (v instanceof Map || v instanceof List) {
				flat.put(e.getKey(), Json.stringify(v));
			} else {
				flat.put(e.getKey(), Json.asString(v));
			}
		}
		return flat;
	}

	private Map<String, Object> toolContent(String text, boolean isError) {
		Map<String, Object> textItem = new LinkedHashMap<String, Object>();
		textItem.put("type", "text");
		textItem.put("text", text);

		List<Object> content = new ArrayList<Object>();
		content.add(textItem);

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("content", content);
		result.put("isError", isError);
		return result;
	}

	private Map<String, Object> successResponse(Object id, Object result) {
		Map<String, Object> resp = new LinkedHashMap<String, Object>();
		resp.put("jsonrpc", "2.0");
		resp.put("id", id);
		resp.put("result", result);
		return resp;
	}

	private Map<String, Object> errorResponse(Object id, int code, String message) {
		Map<String, Object> error = new LinkedHashMap<String, Object>();
		error.put("code", (long) code);
		error.put("message", message);

		Map<String, Object> resp = new LinkedHashMap<String, Object>();
		resp.put("jsonrpc", "2.0");
		resp.put("id", id);
		resp.put("error", error);
		return resp;
	}

	private static Map<String, Object> singletonMap(String key, Object value) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put(key, value);
		return m;
	}

	private void sendJson(HttpServletResponse resp, String json) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		PrintWriter writer = resp.getWriter();
		writer.print(json);
		writer.flush();
	}

	/** Read the request body, or null if it exceeds {@link #MAX_BODY_BYTES}. */
	private String readBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		int total = 0;
		int n;
		while ((n = req.getReader().read(buf)) != -1) {
			total += n;
			if (total > MAX_BODY_BYTES) {
				return null;
			}
			sb.append(buf, 0, n);
		}
		return sb.toString();
	}
}
