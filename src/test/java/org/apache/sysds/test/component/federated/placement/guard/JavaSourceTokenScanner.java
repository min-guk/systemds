/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;

/** Position-preserving Java token scanner; comments and all literal forms are opaque. */
final class JavaSourceTokenScanner {
	record Token(String text, int line, int column) { }

	static List<Token> tokens(String source) {
		List<Token> out = new ArrayList<>();
		int line = 1, column = 1;
		for(int i = 0; i < source.length();) {
			char c = source.charAt(i), n = i + 1 < source.length() ? source.charAt(i + 1) : 0;
			if(c == '/' && n == '/') {
				i += 2; column += 2;
				while(i < source.length() && source.charAt(i) != '\n') { i++; column++; }
			}
			else if(c == '/' && n == '*') {
				i += 2; column += 2;
				while(i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
					if(source.charAt(i++) == '\n') { line++; column = 1; } else column++;
				}
				i = Math.min(source.length(), i + 2); column += 2;
			}
			else if(c == '"' && i + 2 < source.length() && source.startsWith("\"\"\"", i)) {
				i += 3; column += 3;
				while(i + 2 < source.length() && !source.startsWith("\"\"\"", i)) {
					if(source.charAt(i++) == '\n') { line++; column = 1; } else column++;
				}
				i = Math.min(source.length(), i + 3); column += 3;
			}
			else if(c == '"' || c == '\'') {
				char quote = c; i++; column++;
				while(i < source.length()) {
					char q = source.charAt(i++); column++;
					if(q == '\\' && i < source.length()) { i++; column++; }
					else if(q == quote) break;
					else if(q == '\n') { line++; column = 1; }
				}
			}
			else if(Character.isJavaIdentifierStart(c)) {
				int start = i, startColumn = column;
				i++; column++;
				while(i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) { i++; column++; }
				out.add(new Token(source.substring(start, i), line, startColumn));
			}
			else {
				if(!Character.isWhitespace(c)) out.add(new Token(String.valueOf(c), line, column));
				i++;
				if(c == '\n') { line++; column = 1; } else column++;
			}
		}
		return List.copyOf(out);
	}

	static List<String> identifiers(String source) {
		return tokens(source).stream().map(Token::text)
			.filter(s -> !s.isEmpty() && Character.isJavaIdentifierStart(s.charAt(0))).toList();
	}

	static boolean containsSequence(List<Token> tokens, String... sequence) {
		outer: for(int i = 0; i + sequence.length <= tokens.size(); i++) {
			for(int j = 0; j < sequence.length; j++) if(!tokens.get(i + j).text().equals(sequence[j])) continue outer;
			return true;
		}
		return false;
	}

	private JavaSourceTokenScanner() { }
}
