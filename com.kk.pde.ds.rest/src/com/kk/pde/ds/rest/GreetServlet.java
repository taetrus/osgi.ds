package com.kk.pde.ds.rest;

import java.io.IOException;
import java.io.PrintWriter;

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

import com.kk.pde.ds.api.IGreet;

/**
 * REST-like servlet exposing the IGreet service via HTTP Whiteboard.
 * Registered at /api/greet using OSGi HTTP Whiteboard pattern.
 *
 * Endpoints:
 * - GET /api/greet - Returns JSON greeting
 * - GET /api/greet/{name} - Returns personalized greeting
 * - POST /api/greet - Echoes posted message
 */
@Component(
	service = Servlet.class,
	property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/api/greet/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=GreetServlet"
	}
)
public class GreetServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(GreetServlet.class);

	/** Cap the echo body so a huge POST cannot exhaust heap on the shared Jetty. */
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private IGreet greetService;

	@Activate
	public void activate() {
		LOG.info("GreetServlet activated at /api/greet");
	}

	@Reference
	public void setGreetService(IGreet service)
	{
		greetService = service;
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		try {
			String pathInfo = req.getPathInfo();
			String name = null;

			if (pathInfo != null && pathInfo.length() > 1) {
				name = pathInfo.substring(1); // Remove leading slash
			}

			LOG.info("GET /api/greet{} called", name != null ? "/" + name : "");
			greetService.greet();

			String message = name != null
				? "Hello, " + name + "!"
				: "Hello from OSGi HTTP Whiteboard!";

			sendJsonResponse(resp, message);
		} catch (RuntimeException e) {
			sendJsonError(resp, e);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		LOG.info("POST /api/greet called");

		// Read the request body with a hard size cap. A char-buffer read (not readLine)
		// preserves the body's internal line breaks in the echo.
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		int total = 0;
		int n;
		while ((n = req.getReader().read(buf)) != -1) {
			total += n;
			if (total > MAX_BODY_BYTES) {
				resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request too large");
				return;
			}
			sb.append(buf, 0, n);
		}

		try {
			greetService.greet();
			String body = sb.toString();
			String message = "Echo: " + (body.isEmpty() ? "empty" : body);
			sendJsonResponse(resp, message);
		} catch (RuntimeException e) {
			sendJsonError(resp, e);
		}
	}

	private void sendJsonResponse(HttpServletResponse resp, String message) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");

		GreetResponse response = new GreetResponse(message);
		String json = toJson(response);

		PrintWriter writer = resp.getWriter();
		writer.print(json);
		writer.flush();
	}

	private String toJson(GreetResponse response) {
		// Simple JSON serialization without external dependencies
		return String.format(
			"{\"message\":\"%s\",\"timestamp\":%d}",
			escapeJson(response.getMessage()),
			response.getTimestamp()
		);
	}

	/** Emit a JSON error payload with a 500 status, honouring the JSON contract. */
	private void sendJsonError(HttpServletResponse resp, Exception e) throws IOException {
		LOG.error("Request handling failed", e);
		if (resp.isCommitted()) {
			return;
		}
		resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		PrintWriter writer = resp.getWriter();
		writer.print("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
		writer.flush();
	}

	private String escapeJson(String value) {
		if (value == null) return "";
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"':  sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n");  break;
				case '\r': sb.append("\\r");  break;
				case '\t': sb.append("\\t");  break;
				case '\b': sb.append("\\b");  break;
				case '\f': sb.append("\\f");  break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}
}
