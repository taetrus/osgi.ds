package com.kk.pde.ds.mcp.server.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.mcp.api.IMcpTool;

@Component(service = IMcpTool.class)
public class HttpFetchTool implements IMcpTool {

	private static final Logger LOG = LoggerFactory.getLogger(HttpFetchTool.class);
	private static final int MAX_RESPONSE_CHARS = 4096;

	@Activate
	public void activate() {
		LOG.info("HttpFetchTool activated");
	}

	@Override
	public String getName() {
		return "http_fetch";
	}

	@Override
	public String getDescription() {
		return "Fetches a URL and returns the response body (text, max 4KB)";
	}

	@Override
	public String getInputSchema() {
		return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"URL to fetch (HTTP or HTTPS only)\"}},\"required\":[\"url\"]}";
	}

	@Override
	public String execute(Map<String, String> arguments) {
		String urlStr = arguments.get("url");
		if (urlStr == null || urlStr.isEmpty()) {
			return "Error: url is required";
		}

		// Security: only allow HTTP(S)
		if (!urlStr.toLowerCase().startsWith("http://") && !urlStr.toLowerCase().startsWith("https://")) {
			return "Error: only http:// and https:// URLs are allowed";
		}

		HttpURLConnection conn = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.setRequestProperty("User-Agent", "OSGi-MCP-HttpFetch/1.0");

			int status = conn.getResponseCode();
			java.io.InputStream stream = (status >= 200 && status < 300)
				? conn.getInputStream() : conn.getErrorStream();
			if (stream == null) {
				// Error response with no body — getErrorStream() is null here; avoid NPE.
				return "HTTP " + status + " (no body)";
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));

			StringBuilder sb = new StringBuilder();
			char[] buf = new char[1024];
			int totalRead = 0;
			int read;
			while ((read = reader.read(buf)) != -1 && totalRead < MAX_RESPONSE_CHARS) {
				int toAppend = Math.min(read, MAX_RESPONSE_CHARS - totalRead);
				sb.append(buf, 0, toAppend);
				totalRead += toAppend;
			}
			reader.close();

			String truncated = totalRead >= MAX_RESPONSE_CHARS ? " (truncated at 4KB)" : "";
			return "HTTP " + status + truncated + ":\n" + sb.toString();

		} catch (IOException e) {
			return "Error fetching " + urlStr + ": " + e.getMessage();
		} finally {
			if (conn != null) conn.disconnect();
		}
	}
}
