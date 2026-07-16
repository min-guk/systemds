/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

public class FederatedPlannerFactorySourceGuardTest {
	private static final Path MAIN = Paths.get("src/main/java");
	private static final Path FACTORY = MAIN.resolve("org/apache/sysds/hops/ipa/FederatedPlannerFactory.java");
	private static final Path FTYPES = MAIN.resolve("org/apache/sysds/hops/fedplanner/FTypes.java");
	private static final Path TRANSLATOR = MAIN.resolve("org/apache/sysds/parser/DMLTranslator.java");
	private static final Path IPA_PASS = MAIN.resolve("org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java");
	private static final List<String> PLANNERS = Arrays.asList("FederatedPlannerFedAll",
		"FederatedPlannerFedAllMaxFedFoutSinglePass", "FederatedPlannerFedHeuristic",
		"FederatedPlannerDpFedCostBased", "FederatedPlanMinSTCut");
	private static final List<String> ENUMS = Arrays.asList("NONE", "RUNTIME", "COMPILE_FED_ALL",
		"COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS", "COMPILE_FED_HEURISTIC", "COMPILE_COST_BASED",
		"COMPILE_MIN_ST_CUT");
	private static final Pattern FACTORY_CALL = token("FederatedPlannerFactory\\s*\\.\\s*create\\s*\\(");
	private static final Pattern GET_PLANNER = token("\\.\\s*getPlanner\\s*\\(");
	private static final Pattern FACTORY_SIGNATURE = token(
		"public\\s+static\\s+AFederatedPlanner\\s+create\\s*\\(\\s*FederatedPlanner\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");
	private static final Pattern PLANNER_RETURNING_METHOD = token(
		"(?:(?:public|protected|private|static|final|synchronized|native|strictfp)\\s+)*(?:AFederatedPlanner|"
			+ String.join("|", PLANNERS) + ")\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(");

	@Test
	public void lexicalSanitizerAndGlobalFactoryOwnership() throws Exception {
		assertSanitizerIsLexicalAndWhitespaceSafe();
		Map<Path, String> sources = productionSources();
		assertTrue("typed factory is absent", Files.isRegularFile(FACTORY));
		assertEquals("exactly one expected factory source path", Collections.singletonList(FACTORY), sources.keySet().stream()
			.filter(p -> matches(sources.get(p), token("\\bclass\\s+FederatedPlannerFactory\\b")))
			.collect(Collectors.toList()));

		String factory = sources.get(FACTORY);
		assertEquals("exact factory package", 1, count(factory,
			token("\\bpackage\\s+org\\.apache\\.sysds\\.hops\\.ipa\\s*;")));
		assertEquals("one exact typed public static create method", 1, count(factory, FACTORY_SIGNATURE));
		assertEquals("no alternate planner-returning helper/overload", 1, count(factory, PLANNER_RETURNING_METHOD));
		assertForbiddenFactoryIndirection(factory);

		for(String value : ENUMS)
			assertEquals("factory switch must handle " + value, 1,
				count(factory, token("\\bcase\\s+" + Pattern.quote(value) + "\\s*(?::|->)")));
		assertEquals("NONE and RUNTIME require explicit null returns", 2,
			count(factory, token("\\breturn\\s+null\\s*;")));

		for(String planner : PLANNERS) {
			Pattern constructor = token("\\bnew\\s+" + Pattern.quote(planner) + "\\s*\\(");
			List<Occurrence> occurrences = occurrences(sources, constructor);
			assertEquals("one constructor for " + planner + ": " + occurrences, 1, occurrences.size());
			assertEquals("constructor must be owned only by typed factory: " + occurrences, FACTORY,
				occurrences.get(0).path);
		}
		assertFTypesIsDataOnly(sources.get(FTYPES));
	}

	@Test
	public void exactlyTwoProductionCallersAndNoLegacyEnumCalls() throws Exception {
		Map<Path, String> sources = productionSources();
		List<Occurrence> calls = occurrences(sources, FACTORY_CALL);
		assertEquals("exactly two factory calls globally: " + calls, 2, calls.size());
		assertEquals("DMLTranslator must call factory exactly once", 1,
			calls.stream().filter(o -> o.path.equals(TRANSLATOR)).count());
		assertEquals("IPA pass must call factory exactly once", 1,
			calls.stream().filter(o -> o.path.equals(IPA_PASS)).count());
		assertTrue("alternate/delegated factory call path: " + calls,
			calls.stream().allMatch(o -> o.path.equals(TRANSLATOR) || o.path.equals(IPA_PASS)));
		List<Occurrence> legacy = occurrences(sources, GET_PLANNER);
		assertTrue("production enum getPlanner calls remain: " + legacy, legacy.isEmpty());
	}

	private static void assertSanitizerIsLexicalAndWhitespaceSafe() {
		String fixture = "new  RealPlanner \n ( );\n"
			+ "// new FakeLine() Factory.create()\n"
			+ "/* new FakeBlock () Factory . create ( )\n second */\n"
			+ "String s = \"new FakeString() Factory.create() // not comment\";\n"
			+ "char quote = '\\''; char slash = '/';\n"
			+ "FederatedPlannerFactory \n . create \n ( planner );\n";
		String clean = sanitizeJava(fixture);
		assertEquals(1, count(clean, token("\\bnew\\s+RealPlanner\\s*\\(")));
		assertEquals(1, count(clean, FACTORY_CALL));
		for(String fake : Arrays.asList("FakeLine", "FakeBlock", "FakeString"))
			assertFalse("sanitizer leaked " + fake, matches(clean, token("\\b" + fake + "\\b")));
		assertEquals("sanitizer must preserve line count", lineOf(fixture, fixture.length()), lineOf(clean, clean.length()));
	}

	private static void assertFTypesIsDataOnly(String source) {
		for(String planner : PLANNERS)
			assertFalse("FTypes references concrete planner " + planner,
				matches(source, token("\\b" + Pattern.quote(planner) + "\\b")));
		assertFalse("FTypes retains getPlanner", matches(source, token("\\bgetPlanner\\b")));
		assertFalse("FTypes retains planner-returning member", matches(source,
			token("\\bAFederatedPlanner\\b")));
	}

	private static void assertForbiddenFactoryIndirection(String source) {
		Map<String, Pattern> forbidden = new LinkedHashMap<>();
		forbidden.put("reflection", token("\\b(?:Class|Method|Constructor|Field)\\s*\\.|java\\s*\\.\\s*lang\\s*\\.\\s*reflect|\\bMethodHandle\\b"));
		forbidden.put("Object erasure", token("\\bObject\\b"));
		forbidden.put("string/class dispatch", token("\\bString\\b|\\.\\s*(?:getName|getSimpleName|forName)\\s*\\("));
		forbidden.put("functional supplier", token("\\b(?:Supplier|Function|Callable)\\b"));
		forbidden.put("collection registry", token("\\b(?:Map|HashMap|EnumMap|Collection|List|Set|Registry)\\b"));
		forbidden.put("wrapper/delegate", token("\\b(?:wrapper|delegate|registry)\\b"));
		forbidden.put("valueOf dispatch", token("\\.\\s*valueOf\\s*\\("));
		for(Map.Entry<String, Pattern> entry : forbidden.entrySet())
			assertFalse("forbidden factory indirection: " + entry.getKey(), matches(source, entry.getValue()));
	}

	private static Map<Path, String> productionSources() throws IOException {
		Map<Path, String> result = new LinkedHashMap<>();
		try(Stream<Path> paths = Files.walk(MAIN)) {
			for(Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
				.sorted().collect(Collectors.toList()))
				result.put(path, sanitizeJava(Files.readString(path)));
		}
		return result;
	}

	private static List<Occurrence> occurrences(Map<Path, String> sources, Pattern pattern) {
		List<Occurrence> result = new ArrayList<>();
		for(Map.Entry<Path, String> entry : sources.entrySet()) {
			Matcher matcher = pattern.matcher(entry.getValue());
			while(matcher.find())
				result.add(new Occurrence(entry.getKey(), lineOf(entry.getValue(), matcher.start())));
		}
		return result;
	}

	private static int count(String source, Pattern pattern) {
		int result = 0;
		Matcher matcher = pattern.matcher(source);
		while(matcher.find())
			result++;
		return result;
	}

	private static boolean matches(String source, Pattern pattern) {
		return pattern.matcher(source).find();
	}

	private static Pattern token(String expression) {
		return Pattern.compile(expression, Pattern.MULTILINE | Pattern.DOTALL);
	}

	private static int lineOf(String source, int offset) {
		int line = 1;
		for(int i = 0; i < offset; i++)
			if(source.charAt(i) == '\n')
				line++;
		return line;
	}

	private enum LexState {
		CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR
	}

	private static String sanitizeJava(String source) {
		StringBuilder result = new StringBuilder(source.length());
		LexState state = LexState.CODE;
		boolean escaped = false;
		for(int i = 0; i < source.length(); i++) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			switch(state) {
				case CODE:
					if(current == '/' && next == '/') {
						result.append("  ");
						i++;
						state = LexState.LINE_COMMENT;
					}
					else if(current == '/' && next == '*') {
						result.append("  ");
						i++;
						state = LexState.BLOCK_COMMENT;
					}
					else if(current == '"') {
						result.append(' ');
						state = LexState.STRING;
						escaped = false;
					}
					else if(current == '\'') {
						result.append(' ');
						state = LexState.CHAR;
						escaped = false;
					}
					else
						result.append(current);
					break;
				case LINE_COMMENT:
					result.append(current == '\n' ? '\n' : ' ');
					if(current == '\n')
						state = LexState.CODE;
					break;
				case BLOCK_COMMENT:
					if(current == '*' && next == '/') {
						result.append("  ");
						i++;
						state = LexState.CODE;
					}
					else
						result.append(current == '\n' ? '\n' : ' ');
					break;
				case STRING:
				case CHAR:
					result.append(current == '\n' ? '\n' : ' ');
					if(escaped)
						escaped = false;
					else if(current == '\\')
						escaped = true;
					else if((state == LexState.STRING && current == '"') || (state == LexState.CHAR && current == '\''))
						state = LexState.CODE;
					break;
				default:
					throw new IllegalStateException("Unhandled lexical state " + state);
			}
		}
		return result.toString();
	}

	private static final class Occurrence {
		private final Path path;
		private final int line;

		private Occurrence(Path path, int line) {
			this.path = path;
			this.line = line;
		}

		@Override
		public String toString() {
			return path + ":" + line;
		}
	}
}
