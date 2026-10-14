/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.junit.Assert;
import org.junit.Test;

/** Cache-isolation contract for slot-aware multi-return privacy. */
public class MemoMultiOutputPrivacyTest {
	private static final String VALID_RELEASE=
		"{ids:true,recode:[1],cofeePublicRecodeMetadata:true}";

	@Test public void transformMetadataReleaseIsSlotAndPlannerInstanceLocal() throws Exception {
		String prior=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			Fixture fixture=transform(VALID_RELEASE);
			MemoPrivacy metadataFirst=fixture.privacy();
			assertPrivacy(Privacy.PUBLIC,metadataFirst,fixture.metadataBinding());
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,metadataFirst,fixture.primaryBinding());

			MemoPrivacy primaryFirst=fixture.privacy();
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,primaryFirst,fixture.primaryBinding());
			assertPrivacy(Privacy.PUBLIC,primaryFirst,fixture.metadataBinding());

			System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			MemoPrivacy disabled=fixture.privacy();
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,disabled,fixture.metadataBinding());
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,disabled,fixture.primaryBinding());
		}
		finally { restoreProperty(prior); }
	}

	@Test public void unrelatedBuiltinAndMalformedReleaseCannotInheritAuthorization() throws Exception {
		String prior=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			Fixture malformed=transform("{ids:true,recode:[1],cofeePublicRecodeMetadata:true,unexpected:true}");
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,malformed.privacy(),malformed.metadataBinding());

			Fixture qr=builtin("X=rand(rows=4,cols=2,seed=7);[Y,M]=qr(X);"
				+"print(sum(Y));print(sum(M));","Y","M");
			MemoPrivacy unrelated=qr.privacy();
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,unrelated,qr.primaryBinding());
			assertPrivacy(Privacy.PRIVATE_AGGREGATE,unrelated,qr.metadataBinding());
		}
		finally { restoreProperty(prior); }
	}

	private static Fixture transform(String spec) throws Exception {
		return builtin("X=rand(rows=4,cols=2,seed=7);F=as.frame(X);"
			+"[Y,M]=transformencode(target=F,spec=\""+spec+"\");"
			+"print(sum(Y));write(M,\"/tmp/memo-metadata-unused\",format=\"csv\");","Y","M");
	}

	private static Fixture builtin(String script,String primary,String metadata) throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(compiled(script));
		facts.requireComplete(); MemoBindingResolver resolver=new MemoBindingResolver(facts);
		MemoProgramFacts.Occurrence function=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof FunctionOp).findFirst().orElseThrow();
		int target=facts.inputBinding(function.id(),0);
		int sourceOwner=resolver.directSupplyOwners(target).stream().findFirst().orElseThrow();
		return new Fixture(facts,Map.of(sourceOwner,Privacy.PRIVATE_AGGREGATE),
			facts.valueBinding(transientRead(facts,primary)),facts.valueBinding(transientRead(facts,metadata)));
	}

	private static int transientRead(MemoProgramFacts facts,String name) {
		return facts.occurrences().stream().filter(value -> name.equals(value.hop().getName())
			&& value.hop().getOpString().startsWith("TRead")).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
	}

	private static void assertPrivacy(Privacy expected,MemoPrivacy privacy,int binding) {
		MemoPrivacy.Result result=privacy.privacyAt(binding,MemoPrivacy.Context.empty());
		Assert.assertTrue(result.detail(),result.exact());
		Assert.assertEquals(expected,result.privacy());
	}

	private static DMLProgram compiled(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>()); DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}

	private static void restoreProperty(String prior) {
		if(prior==null) System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		else System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,prior);
	}

	private record Fixture(MemoProgramFacts facts,Map<Integer,Privacy> sources,
		int primaryBinding,int metadataBinding) {
		MemoPrivacy privacy() {
			return new MemoPrivacy(facts,new MemoBindingResolver(facts),sources);
		}
	}
}
