package com.kk.pde.ds.mcp.llm;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;

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

import com.kk.pde.ds.mcp.api.Json;

/**
 * HTTP endpoint at POST /llm/chat that triggers an OpenRouter agent conversation.
 *
 * Request body (JSON):
 *   {"message": "your prompt", "model": "optional-model-override"}
 *
 * Response (JSON):
 *   {"response": "LLM answer after tool calls"}
 *
 * The agent automatically uses all tools registered in the OSGi MCP tool registry.
 */
@Component(
	service = Servlet.class,
	property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/llm/chat",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=LlmChatServlet"
	}
)
public class LlmChatServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(LlmChatServlet.class);

	/** Cap the request body so a huge POST cannot exhaust heap on the shared Jetty. */
	private static final int MAX_BODY_BYTES = 1024 * 1024;

	private OpenRouterAgent agent;

	@Activate
	public void activate() {
		LOG.info("LlmChatServlet activated at /llm/chat");
	}

	@Reference
	public void setAgent(OpenRouterAgent agent) {
		this.agent = agent;
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		String body = readBody(req);
		if (body == null) {
			resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request too large");
			return;
		}

		String message;
		String model;
		try {
			Object parsed = Json.parse(body);
			message = Json.getString(parsed, "message");
			model = Json.getString(parsed, "model");
		} catch (Json.JsonException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
				"Request body must be valid JSON: " + e.getMessage());
			return;
		}

		if (message == null || message.trim().isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
				"Request body must be JSON with a 'message' field");
			return;
		}

		LOG.info("LLM chat request: {}", message);
		String response = agent.chat(message, model);

		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		PrintWriter writer = resp.getWriter();
		writer.print(Json.stringify(Collections.singletonMap("response", response)));
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
