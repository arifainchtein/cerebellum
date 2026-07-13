package com.teleonome.cerebellum.health;

public class CheckResult {

	private final String checkName;
	private final Status status;
	private final String message;
	private final Object value;

	public CheckResult(String checkName, Status status, String message, Object value) {
		this.checkName = checkName;
		this.status = status;
		this.message = message;
		this.value = value;
	}

	public String getCheckName() {
		return checkName;
	}

	public Status getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	public Object getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "[" + status + "] " + checkName + ": " + message + " (value=" + value + ")";
	}
}
