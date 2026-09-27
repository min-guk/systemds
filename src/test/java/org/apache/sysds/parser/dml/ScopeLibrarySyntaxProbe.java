/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.parser.dml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.apache.sysds.parser.dml.DmlParser.ExternalFunctionDefExpressionContext;
import org.apache.sysds.parser.dml.DmlParser.FunctionStatementContext;
import org.apache.sysds.parser.dml.DmlParser.ImportStatementContext;
import org.apache.sysds.parser.dml.DmlParser.InternalFunctionDefExpressionContext;
import org.apache.sysds.parser.dml.DmlParser.ProgramrootContext;
import org.apache.sysds.parser.dml.DmlParser.StatementContext;

/** Syntax-only DML parser probe for the bounded current-scope library audit. */
public final class ScopeLibrarySyntaxProbe {
	private ScopeLibrarySyntaxProbe() {
	}

	public static void main(String[] args) throws Exception {
		if(args.length == 0)
			throw new IllegalArgumentException("at least one DML path is required");
		for(String arg : args)
			System.out.println(probe(Path.of(arg)));
	}

	static String probe(Path path) throws IOException {
		String source = Files.readString(path, StandardCharsets.UTF_8);
		DmlLexer lexer = new DmlLexer(CharStreams.fromString(source));
		DmlParser parser = new DmlParser(new CommonTokenStream(lexer));
		ErrorCounter errors = new ErrorCounter();
		lexer.removeErrorListeners();
		parser.removeErrorListeners();
		lexer.addErrorListener(errors);
		parser.addErrorListener(errors);
		ProgramrootContext root = parser.programroot();

		List<String> imports = new ArrayList<>();
		for(StatementContext statement : root.blocks) {
			if(statement instanceof ImportStatementContext imported)
				imports.add("{\"path\":" + quote(unquote(imported.filePath.getText()))
					+ ",\"namespace\":" + quote(imported.namespace.getText()) + "}");
		}
		List<String> functions = new ArrayList<>();
		for(FunctionStatementContext function : root.functionBlocks) {
			if(function instanceof InternalFunctionDefExpressionContext internal)
				functions.add(quote(internal.name.getText()));
			else if(function instanceof ExternalFunctionDefExpressionContext external)
				functions.add(quote(external.name.getText()));
			else
				throw new IllegalStateException("unknown function syntax: " + function.getClass().getName());
		}
		return "{\"path\":" + quote(path.toAbsolutePath().normalize().toString())
			+ ",\"syntaxErrors\":" + errors.count
			+ ",\"topLevelStatementCount\":" + root.blocks.size()
			+ ",\"functionCount\":" + root.functionBlocks.size()
			+ ",\"imports\":[" + String.join(",", imports) + "]"
			+ ",\"functions\":[" + String.join(",", functions) + "]}";
	}

	private static String unquote(String value) {
		return value.length() >= 2 ? value.substring(1, value.length() - 1) : value;
	}

	private static String quote(String value) {
		StringBuilder out = new StringBuilder("\"");
		for(int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch(c) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if(c < 0x20)
						out.append(String.format("\\u%04x", (int)c));
					else
						out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}

	private static final class ErrorCounter extends BaseErrorListener {
		private int count;

		@Override
		public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int position,
			String message, RecognitionException cause) {
			count++;
		}
	}
}
