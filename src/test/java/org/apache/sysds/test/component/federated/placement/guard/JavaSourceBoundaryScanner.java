/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Java source boundary scanner: ignores comments/literals but retains imports and reflection. */
final class JavaSourceBoundaryScanner {
	private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);");
	private static final Pattern REFLECTION = Pattern.compile(
		"(?:Class\\s*\\.\\s*forName|loadClass)\\s*\\(\\s*\"([^\"]+)\"");
	private static final Pattern STRING_OR_CHAR = Pattern.compile("(?s)\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])'");

	static List<String> forbiddenReferences(String source, String forbiddenPrefix) {
		String uncommented = stripComments(source);
		List<String> violations = new ArrayList<>();
		Matcher imports = IMPORT.matcher(uncommented);
		while(imports.find())
			if(imports.group(1).contains(forbiddenPrefix))
				violations.add("import:" + imports.group(1));
		Matcher reflection = REFLECTION.matcher(uncommented);
		while(reflection.find())
			if(reflection.group(1).contains(forbiddenPrefix))
				violations.add("reflection:" + reflection.group(1));
		String executable = STRING_OR_CHAR.matcher(uncommented).replaceAll(" ");
		if(executable.contains(forbiddenPrefix))
			violations.add("executable:" + forbiddenPrefix);
		return List.copyOf(violations);
	}

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	private JavaSourceBoundaryScanner() { }
}
