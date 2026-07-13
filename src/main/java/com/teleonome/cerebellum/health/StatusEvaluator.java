package com.teleonome.cerebellum.health;

import java.util.List;
import java.util.function.Predicate;

/**
 * Evaluates a single observed value (a battery voltage reading, a memory
 * percentage, seconds since a device last checked in, ...) against an
 * ordered list of rules, returning the status of the first rule whose
 * condition matches. Rules are checked in order, so put the most severe
 * condition first.
 */
public class StatusEvaluator<T> {

	public static class Rule<T> {
		private final Predicate<T> condition;
		private final Status status;
		private final String message;

		public Rule(Predicate<T> condition, Status status, String message) {
			this.condition = condition;
			this.status = status;
			this.message = message;
		}
	}

	private final String checkName;
	private final List<Rule<T>> rules;

	public StatusEvaluator(String checkName, List<Rule<T>> rules) {
		this.checkName = checkName;
		this.rules = rules;
	}

	public CheckResult evaluate(T value) {
		for (Rule<T> rule : rules) {
			if (rule.condition.test(value)) {
				return new CheckResult(checkName, rule.status, rule.message, value);
			}
		}
		return new CheckResult(checkName, Status.OK, "no rule matched", value);
	}
}
