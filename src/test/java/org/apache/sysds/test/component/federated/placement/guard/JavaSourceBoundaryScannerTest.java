/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;

public class JavaSourceBoundaryScannerTest {
	private static final String PREFIX = "org.apache.sysds.hops.fedplanner.placement";

	@Test
	public void commentsAndOrdinaryNegativeTestLiteralsAreIgnored() {
		Assert.assertTrue(JavaSourceBoundaryScanner.forbiddenReferences(
			"String negative = \"" + PREFIX + ".selector.Bad\";", PREFIX).isEmpty());
		Assert.assertTrue(JavaSourceBoundaryScanner.forbiddenReferences(
			"// import " + PREFIX + ".Bad;", PREFIX).isEmpty());
		Assert.assertTrue(JavaSourceBoundaryScanner.forbiddenReferences(
			"/* Class.forName(\"" + PREFIX + ".Bad\"); */", PREFIX).isEmpty());
	}

	@Test
	public void importsExecutableFullyQualifiedAndReflectiveReferencesAreRejected() {
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"import " + PREFIX + ".Bad;", PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"class X { " + PREFIX + ".Bad value; }", PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"Class.forName(\"" + PREFIX + ".Bad\");", PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"loader.loadClass(\"" + PREFIX + ".Bad\");", PREFIX).isEmpty());
	}

	@Test
	public void commentMarkersInsideLiteralsCannotHideLaterExecutableViolations() {
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"String harmless = \"//\"; Class.forName(\"" + PREFIX + ".Bad\");", PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"String start = \"/*\"; " + PREFIX + ".Bad value; String end = \"*/\";",
			PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"String harmless = \"/* not a comment */\"; loader.loadClass(\"" + PREFIX + ".Bad\");",
			PREFIX).isEmpty());
	}

	@Test
	public void escapesCharsAndQuoteMarkersPreserveLexicalState() {
		Assert.assertTrue(JavaSourceBoundaryScanner.forbiddenReferences(
			"String harmless = \"escaped \\\" // \\\\ /*\"; char slash = '/'; /* \" ignored */",
			PREFIX).isEmpty());
		Assert.assertFalse(JavaSourceBoundaryScanner.forbiddenReferences(
			"String harmless = \"escaped \\\" // \\\\ /*\"; char quote = '\\\''; "
				+ PREFIX + ".Bad value;", PREFIX).isEmpty());
	}

	@Test
	public void currentOracleTreePassesIncludingItsNegativeTestLiterals() throws Exception {
		Path root = Paths.get("").toAbsolutePath().normalize();
		Path oracle = root.resolve("src/test/java/org/apache/sysds/test/component/federated/placement/oracle");
		List<String> violations = new ArrayList<>();
		try(Stream<Path> paths = Files.walk(oracle)) {
			for(Path source : paths.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				List<String> found = JavaSourceBoundaryScanner.forbiddenReferences(Files.readString(source), PREFIX);
				if(!found.isEmpty())
					violations.add(root.relativize(source) + " -> " + found);
			}
		}
		Assert.assertTrue("oracle boundary violations: " + violations, violations.isEmpty());
	}
}
