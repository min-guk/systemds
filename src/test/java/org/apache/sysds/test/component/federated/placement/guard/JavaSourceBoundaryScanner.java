/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-pass Java lexical boundary scanner for test-oracle independence. */
final class JavaSourceBoundaryScanner {
	private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);");
	private static final Pattern REFLECTION_PREFIX = Pattern.compile(
		"(?:Class\\s*\\.\\s*forName|(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)?loadClass)\\s*\\(\\s*$");

	static List<String> forbiddenReferences(String source, String forbiddenPrefix) {
		LexedSource lexed = lex(source);
		List<String> violations = new ArrayList<>();
		Matcher imports = IMPORT.matcher(lexed.code());
		while(imports.find())
			if(imports.group(1).contains(forbiddenPrefix))
				violations.add("import:" + imports.group(1));
		if(lexed.code().contains(forbiddenPrefix))
			violations.add("executable:" + forbiddenPrefix);
		for(StringLiteral literal : lexed.strings()) {
			Matcher prefix = REFLECTION_PREFIX.matcher(lexed.code().substring(0, literal.start()));
			if(prefix.find() && literal.value().contains(forbiddenPrefix))
				violations.add("reflection:" + literal.value());
		}
		return List.copyOf(violations);
	}

	private static LexedSource lex(String source) {
		StringBuilder code = new StringBuilder(source.length());
		List<StringLiteral> strings = new ArrayList<>();
		State state = State.CODE;
		StringBuilder literal = null;
		int literalStart = -1;
		boolean escaped = false;
		for(int i = 0; i < source.length(); i++) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			switch(state) {
				case CODE:
					if(current == '/' && next == '/') {
						code.append("  "); i++; state = State.LINE_COMMENT;
					}
					else if(current == '/' && next == '*') {
						code.append("  "); i++; state = State.BLOCK_COMMENT;
					}
					else if(current == '"') {
						literalStart = i; literal = new StringBuilder(); code.append(' '); state = State.STRING;
					}
					else if(current == '\'') {
						code.append(' '); state = State.CHAR; escaped = false;
					}
					else code.append(current);
					break;
				case LINE_COMMENT:
					if(current == '\n') { code.append('\n'); state = State.CODE; }
					else code.append(' ');
					break;
				case BLOCK_COMMENT:
					if(current == '*' && next == '/') { code.append("  "); i++; state = State.CODE; }
					else code.append(current == '\n' ? '\n' : ' ');
					break;
				case STRING:
					code.append(current == '\n' ? '\n' : ' ');
					if(escaped) { literal.append(unescape(current)); escaped = false; }
					else if(current == '\\') escaped = true;
					else if(current == '"') { strings.add(new StringLiteral(literalStart, literal.toString())); state = State.CODE; }
					else literal.append(current);
					break;
				case CHAR:
					code.append(current == '\n' ? '\n' : ' ');
					if(escaped) escaped = false;
					else if(current == '\\') escaped = true;
					else if(current == '\'') state = State.CODE;
					break;
			}
		}
		return new LexedSource(code.toString(), List.copyOf(strings));
	}

	private static char unescape(char value) {
		switch(value) {
			case 'n': return '\n';
			case 'r': return '\r';
			case 't': return '\t';
			default: return value;
		}
	}

	private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR }
	private record StringLiteral(int start, String value) { }
	private record LexedSource(String code, List<StringLiteral> strings) { }
	private JavaSourceBoundaryScanner() { }
}
