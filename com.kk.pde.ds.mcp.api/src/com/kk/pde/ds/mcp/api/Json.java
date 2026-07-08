package com.kk.pde.ds.mcp.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small but correct JSON parser and serializer, shared by the MCP server and the
 * LLM bridge. Replaces the earlier string-scanning {@code JsonUtil}/{@code LlmJsonUtil}
 * helpers, whose position-based scanning mis-handled object-valued fields, could not
 * tell a key from an equally-named value, and emitted structurally-broken output when
 * a value was concatenated in unescaped (e.g. a JSON-RPC {@code id}).
 *
 * <p>Parsing produces a plain Java object model:</p>
 * <ul>
 *   <li>object &rarr; {@link Map}&lt;String,Object&gt; (insertion-ordered)</li>
 *   <li>array &rarr; {@link List}&lt;Object&gt;</li>
 *   <li>string &rarr; {@link String}</li>
 *   <li>integral number &rarr; {@link Long}; fractional/exponent number &rarr; {@link Double}</li>
 *   <li>{@code true}/{@code false} &rarr; {@link Boolean}; {@code null} &rarr; {@code null}</li>
 * </ul>
 *
 * <p>Serializing accepts the same model, plus {@link Raw} to splice in a pre-formatted
 * JSON fragment (such as a tool's {@code inputSchema}) verbatim.</p>
 *
 * <p>Pure Java 8, no external dependencies — the same dependency-minimalism that rules
 * out heavier JSON libraries in this project.</p>
 */
public final class Json {

	private Json() {
	}

	/** Wraps an already-formatted JSON fragment so {@link #stringify} emits it verbatim. */
	public static final class Raw {
		final String json;
		public Raw(String json) {
			this.json = json == null ? "null" : json;
		}
	}

	/** Thrown when input is not well-formed JSON. */
	public static final class JsonException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public JsonException(String message) {
			super(message);
		}
	}

	// ---- Parsing -------------------------------------------------------------

	/**
	 * Parse a JSON document into the object model described above.
	 *
	 * @throws JsonException if the text is null or not well-formed JSON
	 */
	public static Object parse(String text) {
		if (text == null) {
			throw new JsonException("null input");
		}
		Parser p = new Parser(text);
		p.skipWs();
		Object value = p.parseValue();
		p.skipWs();
		if (!p.atEnd()) {
			throw new JsonException("trailing content at index " + p.pos);
		}
		return value;
	}

	/**
	 * Parse and return an object, or an empty map if the text is null/blank or does
	 * not parse to a JSON object. Convenience for the many call sites that expect an
	 * object and should degrade gracefully rather than throw.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		if (text == null || text.trim().isEmpty()) {
			return new LinkedHashMap<String, Object>();
		}
		try {
			Object v = parse(text);
			if (v instanceof Map) {
				return (Map<String, Object>) v;
			}
		} catch (JsonException ignored) {
			// fall through to empty map
		}
		return new LinkedHashMap<String, Object>();
	}

	// ---- Typed accessors (null-safe) ----------------------------------------

	/** The value at {@code key} if {@code obj} is a map, else null. */
	public static Object get(Object obj, String key) {
		if (obj instanceof Map) {
			return ((Map<?, ?>) obj).get(key);
		}
		return null;
	}

	/** {@code obj} as a map, or null. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> asObject(Object obj) {
		return (obj instanceof Map) ? (Map<String, Object>) obj : null;
	}

	/** {@code obj} as a list, or null. */
	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object obj) {
		return (obj instanceof List) ? (List<Object>) obj : null;
	}

	/**
	 * {@code obj} as a string. A JSON string returns its value; other scalars return
	 * their textual form; objects/arrays/null return null.
	 */
	public static String asString(Object obj) {
		if (obj == null) return null;
		if (obj instanceof String) return (String) obj;
		if (obj instanceof Map || obj instanceof List) return null;
		if (obj instanceof Double) return trimNumber((Double) obj);
		return String.valueOf(obj);
	}

	/** Look up {@code key} on {@code obj} and return it as a string (see {@link #asString}). */
	public static String getString(Object obj, String key) {
		return asString(get(obj, key));
	}

	// ---- Serializing ---------------------------------------------------------

	/** Serialize the object model (Maps, Lists, String/Number/Boolean/null, {@link Raw}) to JSON. */
	public static String stringify(Object value) {
		StringBuilder sb = new StringBuilder();
		write(value, sb);
		return sb.toString();
	}

	/**
	 * Escape a string's contents for inclusion between JSON quotes. Escapes the
	 * mandatory characters plus all control characters below 0x20 as {@code \\u00XX},
	 * so control bytes never break the surrounding document.
	 */
	public static String escape(String value) {
		if (value == null) return "";
		StringBuilder sb = new StringBuilder(value.length() + 8);
		writeEscaped(value, sb);
		return sb.toString();
	}

	private static void write(Object value, StringBuilder sb) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof Raw) {
			sb.append(((Raw) value).json);
		} else if (value instanceof String) {
			sb.append('"');
			writeEscaped((String) value, sb);
			sb.append('"');
		} else if (value instanceof Double || value instanceof Float) {
			sb.append(trimNumber(((Number) value).doubleValue()));
		} else if (value instanceof Number || value instanceof Boolean) {
			sb.append(value.toString());
		} else if (value instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append('"');
				writeEscaped(String.valueOf(e.getKey()), sb);
				sb.append("\":");
				write(e.getValue(), sb);
			}
			sb.append('}');
		} else if (value instanceof List) {
			sb.append('[');
			boolean first = true;
			for (Object item : (List<?>) value) {
				if (!first) sb.append(',');
				first = false;
				write(item, sb);
			}
			sb.append(']');
		} else {
			// Unknown type — emit its toString as a JSON string, never raw.
			sb.append('"');
			writeEscaped(value.toString(), sb);
			sb.append('"');
		}
	}

	private static void writeEscaped(String s, StringBuilder sb) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
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
						sb.append("\\u");
						String hex = Integer.toHexString(c);
						for (int p = hex.length(); p < 4; p++) sb.append('0');
						sb.append(hex);
					} else {
						sb.append(c);
					}
			}
		}
	}

	/** Render a double without a trailing {@code .0} when it is integral. */
	private static String trimNumber(double d) {
		if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return Double.toString(d);
	}

	// ---- Recursive-descent parser -------------------------------------------

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		boolean atEnd() {
			return pos >= s.length();
		}

		void skipWs() {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					pos++;
				} else {
					break;
				}
			}
		}

		Object parseValue() {
			if (atEnd()) throw new JsonException("unexpected end of input");
			char c = s.charAt(pos);
			switch (c) {
				case '{': return parseObject();
				case '[': return parseArray();
				case '"': return parseString();
				case 't': case 'f': return parseBoolean();
				case 'n': return parseNull();
				default:
					if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
					throw new JsonException("unexpected character '" + c + "' at index " + pos);
			}
		}

		private Map<String, Object> parseObject() {
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			pos++; // consume '{'
			skipWs();
			if (!atEnd() && s.charAt(pos) == '}') { pos++; return map; }
			while (true) {
				skipWs();
				if (atEnd() || s.charAt(pos) != '"') {
					throw new JsonException("expected string key at index " + pos);
				}
				String key = parseString();
				skipWs();
				if (atEnd() || s.charAt(pos) != ':') {
					throw new JsonException("expected ':' at index " + pos);
				}
				pos++; // consume ':'
				skipWs();
				map.put(key, parseValue());
				skipWs();
				if (atEnd()) throw new JsonException("unterminated object");
				char c = s.charAt(pos++);
				if (c == '}') break;
				if (c != ',') throw new JsonException("expected ',' or '}' at index " + (pos - 1));
			}
			return map;
		}

		private List<Object> parseArray() {
			List<Object> list = new ArrayList<Object>();
			pos++; // consume '['
			skipWs();
			if (!atEnd() && s.charAt(pos) == ']') { pos++; return list; }
			while (true) {
				skipWs();
				list.add(parseValue());
				skipWs();
				if (atEnd()) throw new JsonException("unterminated array");
				char c = s.charAt(pos++);
				if (c == ']') break;
				if (c != ',') throw new JsonException("expected ',' or ']' at index " + (pos - 1));
			}
			return list;
		}

		private String parseString() {
			pos++; // consume opening quote
			StringBuilder sb = new StringBuilder();
			while (pos < s.length()) {
				char c = s.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (pos >= s.length()) throw new JsonException("unterminated escape");
					char e = s.charAt(pos++);
					switch (e) {
						case '"':  sb.append('"');  break;
						case '\\': sb.append('\\'); break;
						case '/':  sb.append('/');  break;
						case 'n':  sb.append('\n'); break;
						case 'r':  sb.append('\r'); break;
						case 't':  sb.append('\t'); break;
						case 'b':  sb.append('\b'); break;
						case 'f':  sb.append('\f'); break;
						case 'u':
							if (pos + 4 > s.length()) throw new JsonException("truncated \\u escape");
							String hex = s.substring(pos, pos + 4);
							pos += 4;
							try {
								sb.append((char) Integer.parseInt(hex, 16));
							} catch (NumberFormatException nfe) {
								throw new JsonException("invalid \\u escape: " + hex);
							}
							break;
						default:
							throw new JsonException("invalid escape '\\" + e + "'");
					}
				} else {
					sb.append(c);
				}
			}
			throw new JsonException("unterminated string");
		}

		private Object parseNumber() {
			int start = pos;
			boolean fractional = false;
			if (!atEnd() && s.charAt(pos) == '-') pos++;
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c >= '0' && c <= '9') {
					pos++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					fractional = true;
					pos++;
				} else {
					break;
				}
			}
			String num = s.substring(start, pos);
			try {
				if (fractional) {
					return Double.valueOf(num);
				}
				return Long.valueOf(num);
			} catch (NumberFormatException nfe) {
				throw new JsonException("invalid number '" + num + "'");
			}
		}

		private Boolean parseBoolean() {
			if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
			if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
			throw new JsonException("invalid literal at index " + pos);
		}

		private Object parseNull() {
			if (s.startsWith("null", pos)) { pos += 4; return null; }
			throw new JsonException("invalid literal at index " + pos);
		}
	}
}
