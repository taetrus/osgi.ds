package com.kk.pde.ds.rest;

/**
 * JSON DTO for greeting responses.
 */
public class GreetResponse {

	private final String message;
	private final long timestamp;

	public GreetResponse(String message) {
		this.message = message;
		this.timestamp = System.currentTimeMillis();
	}

	public String getMessage() {
		return message;
	}

	public long getTimestamp() {
		return timestamp;
	}
}
